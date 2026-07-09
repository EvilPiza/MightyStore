package com.mighty.store

import com.google.gson.Gson
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.ModContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.io.path.name
import net.fabricmc.loader.api.SemanticVersion
import net.fabricmc.loader.api.metadata.version.VersionPredicate
import net.minecraft.client.Minecraft
import org.cobalt.Cobalt.configDir
import org.cobalt.addon.AddonManager
import org.cobalt.addon.AddonMetadata
import org.slf4j.LoggerFactory

object AddonStore {

    private const val REGISTRY_URL =
        "https://raw.githubusercontent.com/EvilPiza/addon-registry/refs/heads/main/registry.json"
    const val STORE_ADDON_ID = "mightystore"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val logger = LoggerFactory.getLogger(this::class.java)
    private val gson = Gson()

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "mighty-addon-store").apply { isDaemon = true }
    }

    val addonsDir: Path get() = configDir.resolve("addons").apply(Files::createDirectories)

    private var cachedRegistry: AddonRegistry? = null
    private var cachedAt = 0L

    private val restartPending = mutableSetOf<String>()

    enum class AddonState { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE, RESTART_PENDING, DISABLED }

    sealed class CompatibilityStatus {
        object Compatible : CompatibilityStatus()
        object VerifiedByHash : CompatibilityStatus()
        data class Warning(val reason: WarningReason, val message: String) : CompatibilityStatus()
    }

    enum class WarningReason { UNKNOWN_COBALT, UNVERIFIED_SNAPSHOT, HASH_MISMATCH, VERSION_MISMATCH, UNPARSEABLE_CONSTRAINT }

    data class InstalledAddon(
        val id: String,
        val name: String,
        val version: String,
        val path: Path,
        val enabled: Boolean
    )

    fun <T> async(task: () -> T, onDone: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(task)
            result.exceptionOrNull()?.let { logger.error("Addon store task failed", it) }
            Minecraft.getInstance().execute { onDone(result) }
        }
    }

    fun fetchRegistry(force: Boolean = false): AddonRegistry {
        cachedRegistry?.let {
            if (!force && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return it
        }

        val request = HttpRequest.newBuilder(URI.create(REGISTRY_URL)).GET().build()
        val response = http.send(request, BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Registry fetch failed: HTTP ${response.statusCode()}" }

        val registry = gson.fromJson(response.body(), AddonRegistry::class.java)
        check(registry.schemaVersion == 1) { "Unsupported registry schema ${registry.schemaVersion}" }

        cachedRegistry = registry
        cachedAt = System.currentTimeMillis()
        return registry
    }

    fun installedAddons(): List<InstalledAddon> {
        Files.list(addonsDir).use { paths ->
            return paths
                .filter { Files.isRegularFile(it) }
                .filter { it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled") }
                .map { path ->
                    runCatching {
                        val meta = readManifest(path)
                        InstalledAddon(meta.id, meta.name, meta.version, path, path.name.endsWith(".jar"))
                    }.getOrNull()
                }
                .toList()
                .filterNotNull()
        }
    }

    private fun readManifest(jarPath: Path): AddonMetadata =
        ZipFile(jarPath.toFile()).use { zip ->
            val entry = checkNotNull(zip.getEntry("cobalt.addon.json")) {
                "Missing cobalt.addon.json in ${jarPath.fileName}"
            }
            zip.getInputStream(entry).use { gson.fromJson(it.reader(), AddonMetadata::class.java) }
        }

    fun stateFor(addon: RegistryAddon): AddonState {
        if (addon.id == STORE_ADDON_ID) return AddonState.INSTALLED
        if (addon.id in restartPending) return AddonState.RESTART_PENDING

        val installed = installedAddons().find { it.id == addon.id }
            ?: return AddonState.NOT_INSTALLED
        if (!installed.enabled) return AddonState.DISABLED

        val latest = addon.latest ?: return AddonState.INSTALLED
        return if (isNewer(latest.version, installed.version)) AddonState.UPDATE_AVAILABLE
        else AddonState.INSTALLED
    }

    fun compatibilityStatus(addon: RegistryAddon): CompatibilityStatus? =
        addon.latest?.let { checkCompatibility(it) }

    private fun checkCompatibility(version: RegistryVersion): CompatibilityStatus {
        val constraint = version.cobaltVersion
        if (constraint.isNullOrBlank()) return CompatibilityStatus.Compatible

        val cobaltContainer = FabricLoader.getInstance().getModContainer("cobalt").orElse(null)
            ?: return CompatibilityStatus.Warning(
                WarningReason.UNKNOWN_COBALT,
                "Could not determine the installed Cobalt version"
            )

        if (constraint.equals("SNAPSHOT", ignoreCase = true)) {
            return checkSnapshotCompatibility(version, cobaltContainer)
        }

        val installedVersion = runCatching { SemanticVersion.parse(cobaltContainer.metadata.version.friendlyString) }.getOrNull()
            ?: return CompatibilityStatus.Warning(
                WarningReason.UNKNOWN_COBALT,
                "Could not parse the installed Cobalt version (\"${cobaltContainer.metadata.version.friendlyString}\")"
            )

        val predicate = runCatching { VersionPredicate.parse(constraint) }.getOrNull()
            ?: return CompatibilityStatus.Warning(
                WarningReason.UNPARSEABLE_CONSTRAINT,
                "Couldn't parse this addon's Cobalt version requirement (\"$constraint\")"
            )

        return if (predicate.test(installedVersion)) {
            CompatibilityStatus.Compatible
        } else {
            CompatibilityStatus.Warning(
                WarningReason.VERSION_MISMATCH,
                "This addon expects Cobalt $constraint — you may run into issues"
            )
        }
    }

    private fun checkSnapshotCompatibility(
        version: RegistryVersion,
        cobaltContainer: ModContainer
    ): CompatibilityStatus {
        val expectedHash = version.cobaltSha256
        if (expectedHash.isNullOrBlank()) {
            return CompatibilityStatus.Warning(
                WarningReason.UNVERIFIED_SNAPSHOT,
                "This addon targets an unpinned Cobalt snapshot build — compatibility hasn't been verified"
            )
        }

        val cobaltPath = cobaltContainer.origin.paths.firstOrNull()
            ?: return CompatibilityStatus.Warning(
                WarningReason.UNKNOWN_COBALT,
                "Could not locate the installed Cobalt build to verify it"
            )

        val actualHash = runCatching { sha256(cobaltPath) }.getOrNull()

        return when {
            actualHash == null -> CompatibilityStatus.Warning(
                WarningReason.UNKNOWN_COBALT,
                "Could not read the installed Cobalt build to verify it"
            )
            actualHash.equals(expectedHash, ignoreCase = true) -> CompatibilityStatus.VerifiedByHash
            else -> CompatibilityStatus.Warning(
                WarningReason.HASH_MISMATCH,
                "This addon was tested against a different Cobalt snapshot than the one you're running"
            )
        }
    }


    private fun isNewer(candidate: String, current: String): Boolean = runCatching {
        SemanticVersion.parse(candidate) > SemanticVersion.parse(current)
    }.getOrDefault(candidate != current)

    fun install(addon: RegistryAddon, version: RegistryVersion = requireNotNull(addon.latest)) {
        when (val compatibility = checkCompatibility(version)) {
            is CompatibilityStatus.Warning ->
                logger.warn("${addon.name} ${version.version} [${compatibility.reason}]: ${compatibility.message}")
            CompatibilityStatus.Compatible, CompatibilityStatus.VerifiedByHash -> Unit
        }

        val fileName = "${addon.id}-${version.version}.jar"
        val staging = addonsDir.resolve(PendingOperations.STAGING_DIR).apply(Files::createDirectories)
        val staged = staging.resolve(fileName)

        val request = HttpRequest.newBuilder(URI.create(version.downloadUrl)).GET().build()
        val response = http.send(request, BodyHandlers.ofFile(staged))
        check(response.statusCode() == 200) {
            Files.deleteIfExists(staged)
            "Download failed: HTTP ${response.statusCode()}"
        }

        val actual = sha256(staged)
        if (!actual.equals(version.sha256, ignoreCase = true)) {
            Files.deleteIfExists(staged)
            error("Checksum mismatch for ${addon.name} ${version.version} — refusing to install")
        }

        val manifest = readManifest(staged)
        check(manifest.id == addon.id) {
            Files.deleteIfExists(staged)
            "Manifest id '${manifest.id}' doesn't match registry id '${addon.id}'"
        }

        val existing = installedAddons().find { it.id == addon.id }
        when {
            existing == null -> Files.move(staged, addonsDir.resolve(fileName))
            isLoaded(addon.id) ->
                PendingOperations.queueReplace(addonsDir, existing.path.name, fileName)
            else -> {
                Files.deleteIfExists(existing.path)
                Files.move(staged, addonsDir.resolve(fileName))
            }
        }
        restartPending += addon.id
    }

    fun uninstall(id: String) {
        val installed = installedAddons().find { it.id == id } ?: return
        if (isLoaded(id)) {
            PendingOperations.queueDelete(addonsDir, installed.path.name)
            restartPending += id
        } else {
            Files.deleteIfExists(installed.path)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val installed = installedAddons().find { it.id == id } ?: return
        if (installed.enabled == enabled) return

        val newName =
            if (enabled) installed.path.name.removeSuffix(".disabled")
            else "${installed.path.name}.disabled"

        if (isLoaded(id)) {
            PendingOperations.queueRename(addonsDir, installed.path.name, newName)
            restartPending += id
        } else {
            Files.move(installed.path, installed.path.resolveSibling(newName))
        }
        restartPending += id
    }

    fun restartRequired(): Boolean = restartPending.isNotEmpty()

    private fun isLoaded(id: String) = AddonManager.addons.any { it.first.id == id }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}