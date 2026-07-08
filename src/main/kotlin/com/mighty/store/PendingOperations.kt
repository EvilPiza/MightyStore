package com.mighty.store

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.slf4j.LoggerFactory

object PendingOperations {

    private val logger = LoggerFactory.getLogger(this::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private const val PENDING_FILE = ".pending.json"
    const val STAGING_DIR = ".staging"

    private data class Op(
        val type: String,          // DELETE | REPLACE | RENAME
        val target: String,        // filename in addons/
        val staged: String? = null,  // REPLACE: filename in addons/.staging/
        val newName: String? = null  // RENAME: new filename in addons/
    )

    private data class PendingFile(val ops: MutableList<Op> = mutableListOf())

    @JvmStatic
    fun apply(addonsDir: Path) {
        val file = addonsDir.resolve(PENDING_FILE)
        if (Files.notExists(file)) {
            cleanStaging(addonsDir)
            return
        }

        val pending = runCatching {
            Files.newBufferedReader(file).use { gson.fromJson(it, PendingFile::class.java) }
        }.getOrNull() ?: PendingFile()

        for (op in pending.ops) {
            runCatching {
                when (op.type) {
                    "DELETE" -> Files.deleteIfExists(addonsDir.resolve(op.target))
                    "REPLACE" -> {
                        Files.deleteIfExists(addonsDir.resolve(op.target))
                        val staged = addonsDir.resolve(STAGING_DIR).resolve(op.staged!!)
                        Files.move(staged, addonsDir.resolve(op.staged), REPLACE_EXISTING)
                    }
                    "RENAME" -> Files.move(
                        addonsDir.resolve(op.target),
                        addonsDir.resolve(op.newName!!),
                        REPLACE_EXISTING
                    )
                    else -> logger.warn("Unknown pending op type: ${op.type}")
                }
            }.onFailure { logger.error("Failed to apply pending op $op", it) }
        }

        Files.deleteIfExists(file)
        cleanStaging(addonsDir)
    }

    @Synchronized
    fun queueDelete(addonsDir: Path, target: String) =
        queue(addonsDir, Op("DELETE", target))

    @Synchronized
    fun queueReplace(addonsDir: Path, target: String, stagedName: String) =
        queue(addonsDir, Op("REPLACE", target, staged = stagedName))

    @Synchronized
    fun queueRename(addonsDir: Path, target: String, newName: String) =
        queue(addonsDir, Op("RENAME", target, newName = newName))

    private fun queue(addonsDir: Path, op: Op) {
        val file = addonsDir.resolve(PENDING_FILE)
        val pending = if (Files.exists(file)) {
            runCatching {
                Files.newBufferedReader(file).use { gson.fromJson(it, PendingFile::class.java) }
            }.getOrNull() ?: PendingFile()
        } else PendingFile()

        pending.ops.removeAll { it.target == op.target }
        pending.ops.add(op)

        Files.newBufferedWriter(file).use { gson.toJson(pending, it) }
    }

    private fun cleanStaging(addonsDir: Path) {
        val staging = addonsDir.resolve(STAGING_DIR)
        if (Files.notExists(staging)) return
        runCatching {
            Files.list(staging).use { paths -> paths.forEach(Files::deleteIfExists) }
        }
    }
}
