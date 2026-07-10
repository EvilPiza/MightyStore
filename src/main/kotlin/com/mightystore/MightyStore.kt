package com.mightystore

import com.mightystore.module.StoreModule
import org.cobalt.addon.Addon
import org.cobalt.command.CommandManager
import org.cobalt.module.ModuleManager
import org.slf4j.LoggerFactory

class MightyStore : Addon {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun onLoad() {
        CommandManager.registerCommand(StoreCommand)
        ModuleManager.addModule(StoreModule)

        logger.info("Mighty Store Loaded!")
    }

    override fun onUnload() {
        logger.info("Mighty Store Unloaded!")
    }
}
