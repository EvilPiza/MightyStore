package com.mighty

import org.cobalt.addon.Addon
import org.cobalt.command.CommandManager
import org.slf4j.LoggerFactory

class MightyStore : Addon {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun onLoad() {
        CommandManager.registerCommand(StoreCommand)
        logger.info("Mighty Store Loaded!")
    }

    override fun onUnload() {
        logger.info("Mighty Store Unloaded!")
    }
}
