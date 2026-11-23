package com.mightystore.gui

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.loader.api.FabricLoader

object StoreUiConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = FabricLoader.getInstance().gameDir
        .resolve("config/cobalt/mightystore/ui.json")

    private data class Data(var panelX: Float? = null, var panelY: Float? = null)

    @Synchronized
    fun loadPanelPos(): Pair<Float, Float>? {
        if (Files.notExists(path)) return null
        val data = runCatching {
            Files.newBufferedReader(path).use { gson.fromJson(it, Data::class.java) }
        }.getOrNull() ?: return null
        val x = data.panelX ?: return null
        val y = data.panelY ?: return null
        return x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }

    @Synchronized
    fun savePanelPos(x: Float, y: Float) {
        runCatching {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use {
                gson.toJson(Data(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)), it)
            }
        }
    }
}
