package com.mighty.gui

import com.mighty.module.StoreModule.showCompatibilityWarnings
import com.mighty.store.AddonStore
import com.mighty.store.AddonStore.AddonState
import com.mighty.store.AddonStore.WarningReason
import com.mighty.store.RegistryAddon
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class AddonStoreScreen(private val parent: Screen?) : Screen(Component.literal("Mighty Addon Store")) {

    private sealed interface StoreState {
        data object Loading : StoreState
        data class Loaded(val rows: List<Row>) : StoreState
        data class Error(val message: String) : StoreState
    }

    private data class Row(
        val addon: RegistryAddon,
        var state: AddonState,
        var busyLabel: String? = null,
        var button: Button? = null
    )

    private var storeState: StoreState = StoreState.Loading
    private var scrollOffset = 0

    private val rowHeight = 26
    private var listTop = 0
    private var listBottom = 0
    private var listLeft = 0
    private var listWidth = 0

    private var updateStoreButton: Button? = null

    override fun init() {
        listLeft = width / 2 - 150
        listWidth = 300
        listTop = 40
        listBottom = height - 44

        addRenderableWidget(
            Button.builder(Component.literal("Close")) { onClose() }
                .bounds(width / 2 - 50, height - 28, 100, 20)
                .build()
        )

        updateStoreButton = addRenderableWidget(
            Button.builder(Component.literal("Update")) { onUpdateStoreClicked() }
                .bounds(width - 68, height - 28, 60, 20)
                .build()
        )
        updateStoreButton?.visible = false
        updateStoreButton?.active = false

        loadRegistry()
    }


    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    private fun loadRegistry() {
        storeState = StoreState.Loading
        AddonStore.async(
            task = { AddonStore.fetchRegistry() },
            onDone = { result ->
                result.onSuccess { registry ->
                    val rows = registry.addons.map { addon -> Row(addon, AddonStore.stateFor(addon)) }
                    storeState = StoreState.Loaded(rows)
                    rebuildRowButtons()
                }
                result.onFailure { e ->
                    storeState = StoreState.Error(e.message ?: "Unknown error")
                }
            }
        )
    }

    private fun refreshUpdateButtonVisibility() {
        val storeRow = (storeState as? StoreState.Loaded)
            ?.rows?.find { it.addon.id == AddonStore.STORE_ADDON_ID }
        val canUpdate = storeRow?.state == AddonStore.AddonState.UPDATE_AVAILABLE
        updateStoreButton?.visible = canUpdate
        updateStoreButton?.active = canUpdate
    }

    private fun onUpdateStoreClicked() {
        val storeRow = (storeState as? StoreState.Loaded)
            ?.rows?.find { it.addon.id == AddonStore.STORE_ADDON_ID } ?: return
        AddonStore.async({ AddonStore.install(storeRow.addon) }) { result ->
            result.onFailure { /* your existing error surface */ }
            refreshUpdateButtonVisibility()
        }
    }


    private fun rebuildRowButtons() {
        val current = storeState
        if (current !is StoreState.Loaded) return

        current.rows.forEach { row -> row.button?.let { removeWidget(it) } }

        current.rows.forEachIndexed { index, row ->
            val button = Button.builder(labelFor(row)) { performAction(row) }
                .bounds(listLeft + listWidth - 90, rowY(index) + 3, 80, 20)
                .build()
            button.active = isActionable(row.state)
            row.button = button
            addRenderableWidget(button)
        }
        updateRowVisibility()
    }

    private fun rowY(index: Int): Int = listTop + index * rowHeight - scrollOffset

    private fun updateRowVisibility() {
        val current = storeState
        if (current !is StoreState.Loaded) return
        current.rows.forEachIndexed { index, row ->
            val y = rowY(index)
            val visible = y + rowHeight > listTop && y < listBottom
            row.button?.let {
                it.setPosition(it.x, y + 3)
                it.visible = visible
            }
        }
    }

    private fun isActionable(state: AddonState): Boolean = when (state) {
        AddonState.RESTART_PENDING -> false
        else -> true
    }

    private fun labelFor(row: Row): Component {
        row.busyLabel?.let { return Component.literal(it) }
        return when (row.state) {
            AddonState.NOT_INSTALLED -> Component.literal("Install")
            AddonState.INSTALLED -> Component.literal("Uninstall")
            AddonState.UPDATE_AVAILABLE -> Component.literal("Update")
            AddonState.DISABLED -> Component.literal("Enable")
            AddonState.RESTART_PENDING -> Component.literal("Pending")
        }
    }

    private fun performAction(row: Row) {
        val id = row.addon.id
        when (row.state) {
            AddonState.NOT_INSTALLED, AddonState.UPDATE_AVAILABLE -> {
                row.busyLabel = "Installing..."
                setBusy(row)
                AddonStore.async(
                    task = { AddonStore.install(row.addon) },
                    onDone = { result -> onActionDone(row, result) }
                )
            }
            AddonState.INSTALLED -> {
                row.busyLabel = "Removing..."
                setBusy(row)
                AddonStore.async(
                    task = { AddonStore.uninstall(id) },
                    onDone = { result -> onActionDone(row, result) }
                )
            }
            AddonState.DISABLED -> {
                row.busyLabel = "Enabling..."
                setBusy(row)
                AddonStore.async(
                    task = { AddonStore.setEnabled(id, true) },
                    onDone = { result -> onActionDone(row, result) }
                )
            }
            AddonState.RESTART_PENDING -> Unit
        }
    }

    private fun setBusy(row: Row) {
        row.button?.active = false
        row.button?.message = labelFor(row)
    }

    private fun onActionDone(row: Row, result: Result<Unit>) {
        row.busyLabel = null
        result.onSuccess { row.state = AddonStore.stateFor(row.addon) }
        result.onFailure { row.button?.message = Component.literal("Error").withStyle(ChatFormatting.RED) }
        row.button?.active = isActionable(row.state)
        if (result.isSuccess) row.button?.message = labelFor(row)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val current = storeState
        if (current is StoreState.Loaded) {
            val maxScroll = (current.rows.size * rowHeight - (listBottom - listTop)).coerceAtLeast(0)
            scrollOffset = (scrollOffset - (scrollY * rowHeight).toInt()).coerceIn(0, maxScroll)
            updateRowVisibility()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)

        when (val current = storeState) {
            is StoreState.Loading -> {
                val text = "Loading addons..."
                graphics.text(font, text, width / 2 - font.width(text) / 2, height / 2, 0xFFAAAAAA.toInt(), true)
            }
            is StoreState.Error -> {
                val label = Component.literal("Error: ${current.message}").withStyle(ChatFormatting.RED)
                graphics.text(font, label, width / 2 - font.width(label) / 2, height / 2, 0xFFFF5555.toInt(), true)
            }
            is StoreState.Loaded -> {
                val displayRows = current.rows.filterNot { it.addon.id == AddonStore.STORE_ADDON_ID }

                displayRows.forEachIndexed { index, row ->
                    val y = rowY(index)
                    if (y + rowHeight <= listTop || y >= listBottom) return@forEachIndexed

                    val warning = AddonStore.compatibilityStatus(row.addon) as? AddonStore.CompatibilityStatus.Warning
                    var textLeft = listLeft
                    if (warning != null) {
                        val glyph = "⚠"
                        graphics.text(font, glyph, listLeft, y + 2, 0xFFFFCC00.toInt(), true)
                        textLeft = listLeft + font.width(glyph) + 4
                    }

                    val nameLine = "${row.addon.name} (${row.addon.latest?.version ?: "?"})"
                    graphics.text(font, nameLine, textLeft, y + 2, 0xFFFFFFFF.toInt(), true)
                    val stateLine = row.state.name.lowercase().replace('_', ' ')
                    graphics.text(font, stateLine, textLeft, y + 13, 0xFF999999.toInt(), true)

                    if (warning != null && showCompatibilityWarnings) {
                        graphics.text(font, warning.message, textLeft, y + 24, 0xFFFFCC00.toInt(), true)
                    }
                }

                if (AddonStore.restartRequired()) {
                    val restartWarning = Component.literal("Restart required to apply changes").withStyle(ChatFormatting.YELLOW)
                    graphics.text(font, restartWarning, width / 2 - font.width(restartWarning) / 2, height - 40, 0xFFFFFF55.toInt(), true)
                }
            }
        }
    }
}