package com.mightystore

import com.mightystore.module.StoreModule
import com.mightystore.store.AddonStore
import net.fabricmc.api.ClientModInitializer
import org.cobalt.addon.Addon
import org.cobalt.command.CommandManager
import org.cobalt.module.ModuleManager
import org.slf4j.LoggerFactory

class MightyStore : Addon, ClientModInitializer {

    private val logger = LoggerFactory.getLogger(this::class.java)
    private var initialized = false

    override fun onInitializeClient() {
        init()
    }

    override fun onLoad() {
        init()
    }

    private fun init() {
        if (initialized) return
        initialized = true

        CommandManager.registerCommand(StoreCommand)
        runCatching { ModuleManager.addModule(StoreModule) }
            .onFailure { logger.debug("Store module was already registered", it) }

        checkForSelfUpdate()
        logger.info("Mighty Store Loaded!")
    }

    override fun onUnload() {
        logger.info("Mighty Store Unloaded!")
    }

    private fun checkForSelfUpdate() {
        AddonStore.async(
            task = {
                val registry = AddonStore.fetchRegistry(force = true)
                registry.addons.find { it.id == AddonStore.STORE_ADDON_ID }
            },
            onDone = { result ->
                val addon = result.getOrNull() ?: return@async
                if (AddonStore.stateFor(addon) != AddonStore.AddonState.UPDATE_AVAILABLE) return@async

                logger.info("MightyStore update available, installing automatically")
                AddonStore.async(
                    task = { AddonStore.install(addon) },
                    onDone = { installResult ->
                        installResult.onSuccess {
                            logger.info("MightyStore updated; restart required to apply")
                        }.onFailure { e ->
                            logger.error("MightyStore auto-update failed", e)
                        }
                    }
                )
            }
        )
    }
}
