package com.mighty

import com.mighty.gui.AddonStoreScreen
import com.mighty.store.AddonStore
import net.minecraft.client.Minecraft
import org.cobalt.command.Command
import org.cobalt.command.annotation.DefaultHandler
import org.cobalt.command.annotation.SubCommand
import org.slf4j.LoggerFactory

object StoreCommand : Command(
    name = "mightystore",
    aliases = listOf("store", "ultramegachudmod")
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @DefaultHandler
    fun main() {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().gui.setScreen(AddonStoreScreen(Minecraft.getInstance().gui.screen()))
        }
    }

    @SubCommand("list")
    fun list() {
        AddonStore.async(
            task = { AddonStore.fetchRegistry() },
            onDone = { result ->
                result.onSuccess { registry ->
                    registry.addons.forEach { addon ->
                        val state = AddonStore.stateFor(addon)
                        logger.info("${addon.id} — ${addon.name} (${addon.latest?.version ?: "?"}) [$state]")
                    }
                }
                result.onFailure { e -> logger.error("Failed to load addon list: ${e.message}") }
            }
        )
    }

    @SubCommand("install")
    fun install(id: String) {
        AddonStore.async(
            task = {
                val registry = AddonStore.fetchRegistry()
                val addon = registry.addons.find { it.id == id }
                    ?: error("No addon with id '$id'")
                AddonStore.install(addon)
            },
            onDone = { result ->
                result.onSuccess {
                    val suffix = if (AddonStore.restartRequired()) " (restart required)" else ""
                    logger.info("Installed '$id'$suffix")
                }
                result.onFailure { e -> logger.error("Install of '$id' failed: ${e.message}") }
            }
        )
    }

    @SubCommand("uninstall")
    fun uninstall(id: String) {
        AddonStore.async(
            task = { AddonStore.uninstall(id) },
            onDone = { result ->
                result.onSuccess {
                    val suffix = if (AddonStore.restartRequired()) " (restart required)" else ""
                    logger.info("Uninstalled '$id'$suffix")
                }
                result.onFailure { e -> logger.error("Uninstall of '$id' failed: ${e.message}") }
            }
        )
    }
}