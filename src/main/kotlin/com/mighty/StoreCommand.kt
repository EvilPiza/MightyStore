package com.mighty

import org.cobalt.command.Command
import org.cobalt.command.annotation.DefaultHandler

object StoreCommand : Command(
    name = "mightystore",
    aliases = listOf("store", "ms", "ultramegachudmod")
) {
    @DefaultHandler
    fun main() {

    }
}