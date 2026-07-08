package com.example

import org.cobalt.addon.Addon
import org.slf4j.LoggerFactory

class ExampleAddon : Addon {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun onLoad() {
        logger.info("ExampleAddon Loaded!")
    }

    override fun onUnload() {
        logger.info("ExampleAddon Unloaded!")
    }

}
