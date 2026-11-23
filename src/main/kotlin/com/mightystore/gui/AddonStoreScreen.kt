package com.mightystore.gui

import com.mightystore.module.StoreModule.showCompatibilityWarnings
import com.mightystore.store.AddonStore
import com.mightystore.store.AddonStore.AddonState
import com.mightystore.store.RegistryAddon
import java.awt.Color
import java.awt.Desktop
import java.net.URI
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import org.cobalt.ui.UIScreen
import org.cobalt.ui.helper.ScrollHelper
import org.cobalt.ui.theme.ThemeManager
import org.cobalt.util.client.WindowUtils.windowHeight
import org.cobalt.util.client.WindowUtils.windowWidth
import org.cobalt.util.input.Mouse
import org.cobalt.util.render.SkiaRenderer

class AddonStoreScreen(private val parent: Screen?) : UIScreen() {

    private data class Row(
        val addon: RegistryAddon,
        val repoUrl: String?,
        val nameW: Float,
        var state: AddonState,
        var busy: String? = null,
        var targetExpand: Boolean = false,
        var expand: Float = 0f,
        var menuOpen: Boolean = false,
        var warning: String? = null,
        var detailsH: Float = -1f,
        var detailsW: Float = -1f,
        var meta: String = "",
        var metaW: Float = -1f,
        var metaElided: String = "",
    )

    private var rows: List<Row>? = null
    private var error: String? = null
    private val scroll = ScrollHelper(28f)

    private var panelX = Float.NaN
    private var panelY = Float.NaN
    private var dragging = false
    private var dragDx = 0f
    private var dragDy = 0f
    private var loadedPos = false
    private var mx = 0f
    private var my = 0f
    private var lastHoverMx = Float.NaN
    private var lastHoverMy = Float.NaN
    private var lastHoverScroll = Float.NaN
    private var menuItemHover = -1
    private var panelCache = Rect(0f, 0f, 0f, 0f)

    private val theme get() = ThemeManager.activeTheme

    override fun added() {
        rows = null
        error = null
        scroll.reset()
        if (!loadedPos) {
            val p = StoreUiConfig.loadPanelPos()
            if (p != null) {
                val r = panelRectFor(windowWidth, windowHeight)
                panelX = p.first * (windowWidth - r.w).coerceAtLeast(0f)
                panelY = p.second * (windowHeight - r.h).coerceAtLeast(0f)
            }
            loadedPos = true
        }
        AddonStore.async({ AddonStore.fetchRegistry() to AddonStore.installedById() }) { result ->
            result.onSuccess { (reg, installed) ->
                rows = reg.addons.filter { it.id != AddonStore.STORE_ADDON_ID }.map {
                    Row(it, repoUrl(it), SkiaRenderer.textWidth(SkiaRenderer.boldFont, it.name, 14f), AddonStore.stateFor(it, installed), warning = warningFor(it)).also { r -> r.meta = metaText(r) }
                }
            }.onFailure { error = it.message ?: "Unknown error" }
        }
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun extractBlurredBackground(graphics: GuiGraphicsExtractor) = Unit
    override fun extractMenuBackground(graphics: GuiGraphicsExtractor) = Unit

    override fun renderSkia() {
        val curMx = Mouse.mouseX; val curMy = Mouse.mouseY
        val moved = curMx != lastHoverMx || curMy != lastHoverMy || scroll.scrollOffset != lastHoverScroll
        if (moved) { mx = curMx; my = curMy; lastHoverMx = curMx; lastHoverMy = curMy; lastHoverScroll = scroll.scrollOffset; menuItemHover = -1 }
        rows?.let { list ->
            var animating = false
            for (row in list) if (kotlin.math.abs((if (row.targetExpand) 1f else 0f) - row.expand) > 0.01f) { animating = true; break }
            if (animating) {
                val dt = (minecraft.deltaTracker.getRealtimeDeltaTicks() / 20f).coerceIn(0f, 0.1f)
                val k = 1f - kotlin.math.exp(-10f * dt)
                for (row in list) {
                    val target = if (row.targetExpand) 1f else 0f
                    row.expand += (target - row.expand) * k
                    if (kotlin.math.abs(target - row.expand) < 0.01f) row.expand = target
                }
            }
        }
        val p = panel().also { panelCache = it }
        SkiaRenderer.roundedRect(p.x, p.y, p.w, p.h, 12f, theme.backgroundPrimary)
        SkiaRenderer.roundedOutline(p.x, p.y, p.w, p.h, 1f, 12f, theme.border)

        val x = p.x + PAD
        val w = p.w - PAD * 2
        val y = p.y + PAD

        SkiaRenderer.text(SkiaRenderer.boldFont, "Mighty Store", x, y, 22f, theme.textPrimary)
        val subtitle = when {
            error != null -> "Could not load registry"
            rows == null -> "Fetching registry"
            else -> "${rows!!.size} addon${if (rows!!.size == 1) "" else "s"} available"
        }
        SkiaRenderer.text(SkiaRenderer.regularFont, subtitle, x, y + 30f, 12f, theme.textMuted)

        val closeX = x + w - 28f
        val closeY = y - 2f
        val closeHover = hover(closeX, closeY, 28f, 28f)
        SkiaRenderer.roundedRect(closeX, closeY, 28f, 28f, 6f, if (closeHover) theme.backgroundSecondary else theme.transparent)
        SkiaRenderer.roundedOutline(closeX, closeY, 28f, 28f, 1f, 6f, if (closeHover) theme.border else theme.transparent)
        val c = if (closeHover) theme.textPrimary else theme.textMuted
        SkiaRenderer.line(closeX + 10f, closeY + 10f, closeX + 18f, closeY + 18f, 1.5f, c)
        SkiaRenderer.line(closeX + 18f, closeY + 10f, closeX + 10f, closeY + 18f, 1.5f, c)

        val listY = y + HEADER_H
        val listH = p.y + p.h - FOOTER_H - listY
        when {
            error != null -> centered(error!!, p, theme.error)
            rows == null -> centered("Loading addons...", p, theme.textMuted)
            else -> drawRows(rows!!, x, listY, w, listH)
        }

        val footerY = p.y + p.h - FOOTER_H
        SkiaRenderer.line(x, footerY, x + w, footerY, 1f, theme.border.alpha(80))
        if (AddonStore.restartRequired()) {
            SkiaRenderer.text(SkiaRenderer.regularFont, "Restart required to apply changes", x, footerY + 16f, 11f, theme.warning)
        }
        val hint = "Esc to close"
        SkiaRenderer.text(SkiaRenderer.regularFont, hint, x + w - SkiaRenderer.textWidth(SkiaRenderer.regularFont, hint, 11f), footerY + 16f, 11f, theme.textMuted)
    }

    private fun rowHeights(list: List<Row>): FloatArray {
        val heights = FloatArray(list.size)
        for (i in list.indices) heights[i] = rowH(list[i])
        return heights
    }

    private fun drawRows(list: List<Row>, x: Float, y: Float, w: Float, h: Float) {
        val heights = rowHeights(list)
        var contentH = 0f
        for (i in heights.indices) contentH += heights[i] + if (i < heights.lastIndex) GAP else 0f
        scroll.updateMaxScroll(contentH, h)
        val menuHover = menuHovering(heights, list, x, y, w)
        SkiaRenderer.pushScissor(x - 2f, y, w + 4f, h)
        var ry = y - scroll.scrollOffset
        for (i in list.indices) {
            val rh = heights[i]
            if (ry + rh >= y && ry <= y + h) drawRow(list[i], x, ry, w, rh, menuHover)
            ry += rh + GAP
        }
        SkiaRenderer.popScissor()

        ry = y - scroll.scrollOffset
        for (i in list.indices) {
            val rh = heights[i]
            if (list[i].menuOpen && ry + ROW_H >= y && ry <= y + h) drawMenu(list[i], x, ry, w, h)
            ry += rh + GAP
        }
    }

    private fun drawRow(row: Row, x: Float, y: Float, w: Float, h: Float, menuHover: Boolean) {
        val hover = hover(x, y, w, h) && !menuHover
        SkiaRenderer.roundedRect(x, y, w, h, 8f, if (hover || row.menuOpen) theme.backgroundSecondary else theme.backgroundPrimary)
        SkiaRenderer.roundedOutline(x, y, w, h, 1f, 8f, if (hover || row.targetExpand || row.menuOpen) theme.border else theme.border.alpha(90))

        val actionW = 76f
        val menuW = 28f
        val menuX = x + w - 12f - menuW
        val actionX = menuX - 8f - actionW
        val textMax = (actionX - 10f - (x + 14f)).coerceAtLeast(40f)

        SkiaRenderer.text(SkiaRenderer.boldFont, row.addon.name, x + 14f, y + 11f, 14f, theme.textPrimary)
        SkiaRenderer.text(SkiaRenderer.regularFont, "v${row.addon.latest?.version ?: "?"}", x + 14f + row.nameW + 8f, y + 12f, 11f, theme.textMuted)

        if (row.metaW != textMax) { row.metaElided = ellipsize(row.meta, textMax, 11f); row.metaW = textMax }
        SkiaRenderer.text(SkiaRenderer.regularFont, row.metaElided, x + 14f, y + 30f, 11f, stateColor(row.state))

        drawAction(row, actionX, y + 14f, actionW, 28f)
        drawMenuButton(menuX, y + 14f, menuW, 28f, row.menuOpen)
        if (row.warning != null) SkiaRenderer.circle(x + w - 14f, y + 10f, 3f, theme.warning)

        val detailsH = detailsH(row, w)
        val visibleH = detailsH * row.expand
        if (visibleH > 0.5f) {
            val dy0 = y + ROW_H + 2f
            SkiaRenderer.pushScissor(x, dy0, w, visibleH)
            SkiaRenderer.line(x + 14f, dy0, x + w - 14f, dy0, 1f, theme.border.alpha(70))
            var dy = dy0 + 10f
            val desc = row.addon.description?.takeIf { it.isNotBlank() } ?: "No description provided."
            SkiaRenderer.wrappedText(SkiaRenderer.regularFont, desc, x + 14f, dy, w - 28f, 11.5f, theme.textSecondary, 1.35f)
            dy += SkiaRenderer.wrappedTextHeight(SkiaRenderer.regularFont, desc, w - 28f, 11.5f, 1.35f) + 8f
            listOfNotNull(
                row.addon.latest?.cobaltVersion?.takeIf { it.isNotBlank() }?.let { "Cobalt" to it },
                row.addon.repo?.takeIf { it.isNotBlank() }?.let { "Repo" to it },
            ).forEach { (k, v) ->
                SkiaRenderer.text(SkiaRenderer.regularFont, "$k:", x + 14f, dy, 10.5f, theme.textMuted)
                SkiaRenderer.text(SkiaRenderer.regularFont, ellipsize(v, w - 80f, 10.5f), x + 62f, dy, 10.5f, theme.textSecondary)
                dy += 15f
            }
            row.warning?.let {
                if (showCompatibilityWarnings) SkiaRenderer.wrappedText(SkiaRenderer.regularFont, it, x + 14f, dy + 2f, w - 28f, 10.5f, theme.warning, 1.3f)
            }
            SkiaRenderer.popScissor()
        }

        drawExpandTab(x, y, w, h, row)
    }
private fun drawAction(row: Row, x: Float, y: Float, w: Float, h: Float) {
        val enabled = row.state != AddonState.RESTART_PENDING && row.busy == null
        val hover = hover(x, y, w, h)
        val primary = row.state == AddonState.NOT_INSTALLED || row.state == AddonState.UPDATE_AVAILABLE
        val bg = when {
            !enabled -> theme.backgroundSecondary.alpha(120)
            primary -> theme.accentPrimary.alpha(if (hover) 255 else 210)
            hover -> theme.backgroundPrimary
            else -> theme.transparent
        }
        val border = when {
            !enabled -> theme.border.alpha(80)
            primary -> theme.accentPrimary
            hover -> theme.textMuted
            else -> theme.border
        }
        val fg = if (!enabled) theme.textDisabled else if (primary) theme.textPrimary else theme.textSecondary
        SkiaRenderer.roundedRect(x, y, w, h, 6f, bg)
        SkiaRenderer.roundedOutline(x, y, w, h, 1f, 6f, border)
        val label = row.busy ?: actionLabel(row.state)
        SkiaRenderer.text(SkiaRenderer.regularFont, label, x + (w - SkiaRenderer.textWidth(SkiaRenderer.regularFont, label, 12f)) / 2f, y + 8f, 12f, fg)
    }

    private fun drawMenuButton(x: Float, y: Float, w: Float, h: Float, open: Boolean) {
        val hover = hover(x, y, w, h)
        SkiaRenderer.roundedRect(x, y, w, h, 6f, if (hover || open) theme.backgroundPrimary else theme.transparent)
        SkiaRenderer.roundedOutline(x, y, w, h, 1f, 6f, if (hover || open) theme.border else theme.transparent)
        val c = if (hover || open) theme.textPrimary else theme.textMuted
        val cx = x + w / 2f
        val cy = y + h / 2f
        SkiaRenderer.circle(cx, cy - 5f, 1.6f, c); SkiaRenderer.circle(cx, cy, 1.6f, c); SkiaRenderer.circle(cx, cy + 5f, 1.6f, c)
    }

    private fun menuBounds(rowY: Float, listX: Float, listW: Float, listTop: Float, listH: Float): Rect {
        val mw = 150f; val mh = 64f
        val mx = listX + listW - 12f - mw
        val below = rowY + ROW_H - 2f
        val my = if (below + mh <= listTop + listH) below else (rowY - mh + 2f).coerceAtLeast(listTop)
        return Rect(mx, my, mw, mh)
    }

    private fun drawMenu(row: Row, listX: Float, rowY: Float, listW: Float, listH: Float) {
        val listTop = panelCache.y + PAD + HEADER_H
        val m = menuBounds(rowY, listX, listW, listTop, listH)
        val ih = 28f; val pad = 4f
        SkiaRenderer.roundedRect(m.x, m.y, m.w, m.h, 8f, theme.backgroundPrimary)
        SkiaRenderer.roundedOutline(m.x, m.y, m.w, m.h, 1f, 8f, theme.border)
        val repo = row.repoUrl
        if (menuItemHover < 0 && hover(m.x, m.y, m.w, m.h)) {
            menuItemHover = when {
                hover(m.x + 4f, m.y + pad, m.w - 8f, ih) -> 0
                hover(m.x + 4f, m.y + pad + ih, m.w - 8f, ih) -> 1
                else -> 2
            }
        }
        drawMenuItem("Open Repo", repo != null, m.x, m.y + pad, m.w, ih, menuItemHover == 0)
        drawMenuItem("Report Issue", repo != null, m.x, m.y + pad + ih, m.w, ih, menuItemHover == 1)
    }

    private fun drawMenuItem(label: String, enabled: Boolean, mx: Float, iy: Float, mw: Float, ih: Float, itemHover: Boolean) {
        if (itemHover && enabled) SkiaRenderer.roundedRect(mx + 4f, iy, mw - 8f, ih, 6f, theme.backgroundSecondary)
        val fg = when { !enabled -> theme.textDisabled; itemHover -> theme.textPrimary; else -> theme.textSecondary }
        SkiaRenderer.text(SkiaRenderer.regularFont, label, mx + 12f, iy + 8f, 12f, fg)
    }

    private fun repoUrl(addon: RegistryAddon): String? {
        val explicit = addon.repo?.trim()?.takeIf { it.isNotBlank() }
        if (explicit != null) return if (explicit.startsWith("http")) explicit.removeSuffix("/") else "https://github.com/${explicit.removePrefix("/").removeSuffix("/")}"
        val url = addon.latest?.downloadUrl ?: return null
        val m = Regex("^https?://github\\.com/([^/]+/[^/]+)/").find(url) ?: return null
        return "https://github.com/${m.groupValues[1]}"
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick)
        mx = Mouse.mouseX; my = Mouse.mouseY
        val p = panel().also { panelCache = it }
        val x = p.x + PAD
        val w = p.w - PAD * 2
        val listY = p.y + PAD + HEADER_H
        val listH = p.y + p.h - FOOTER_H - listY

        if (hover(x + w - 28f, p.y + PAD - 2f, 28f, 28f)) { onClose(); return true }

        rows?.let { list ->
            val heights = rowHeights(list)
            val openIdx = list.indexOfFirst { it.menuOpen }
            if (openIdx >= 0) {
                val menuRow = list[openIdx]
                var ry = listY - scroll.scrollOffset
                for (i in 0 until openIdx) ry += heights[i] + GAP
                val menuX = x + w - 12f - 28f
                if (hover(menuX, ry + 14f, 28f, 28f)) { menuRow.menuOpen = false; return true }
                if (handleMenuClick(menuRow, ry, x, w, listY, listH)) return true
            }
            if (hover(x, listY, w, listH)) {
                var ry = listY - scroll.scrollOffset
                for (i in list.indices) {
                    val row = list[i]; val rh = heights[i]
                    if (hover(x, ry, w, rh)) {
                        val menuX = x + w - 12f - 28f
                        val actionX = menuX - 8f - 76f
                        when {
                            hover(menuX, ry + 14f, 28f, 28f) -> { val open = !row.menuOpen; list.forEach { it.menuOpen = false }; row.menuOpen = open }
                            hover(actionX, ry + 14f, 76f, 28f) -> { list.forEach { it.menuOpen = false }; performAction(row) }
                            else -> { list.forEach { it.menuOpen = false }; row.targetExpand = !row.targetExpand }
                        }
                        return true
                    }
                    ry += rh + GAP
                }
            }
        }

        rows?.forEach { it.menuOpen = false }
        if (hover(p.x, p.y, p.w, PAD + HEADER_H)) {
            dragging = true; dragDx = Mouse.mouseX - p.x; dragDy = Mouse.mouseY - p.y
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun handleMenuClick(row: Row, rowY: Float, listX: Float, listW: Float, listTop: Float, listH: Float): Boolean {
        val m = menuBounds(rowY, listX, listW, listTop, listH)
        if (!hover(m.x, m.y, m.w, m.h)) { row.menuOpen = false; return false }
        val repo = row.repoUrl
        val ih = 28f; val pad = 4f
        if (hover(m.x + 4f, m.y + pad, m.w - 8f, ih)) { row.menuOpen = false; menuItemHover = -1; repo?.let(::openUrl); return true }
        if (hover(m.x + 4f, m.y + pad + ih, m.w - 8f, ih)) { row.menuOpen = false; menuItemHover = -1; repo?.let { openUrl(if (it.contains("github.com")) "$it/issues/new/choose" else it) }; return true }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (event.button() == 0 && dragging) {
            dragging = false
            savePanelPos()
        }
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (dragging && event.button() == 0) {
            mx = Mouse.mouseX; my = Mouse.mouseY
            val p = panelCache
            panelX = (mx - dragDx).coerceIn(0f, (windowWidth - p.w).coerceAtLeast(0f))
            panelY = (my - dragDy).coerceIn(0f, (windowHeight - p.h).coerceAtLeast(0f))
            panelCache = Rect(panelX, panelY, p.w, p.h)
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        rows?.forEach { it.menuOpen = false }
        scroll.scroll(scrollY); lastHoverScroll = Float.NaN
        return rows != null || super.mouseScrolled(x, y, scrollX, scrollY)
    }

    private fun performAction(row: Row) {
        if (row.busy != null) return
        val id = row.addon.id
        val task: () -> Unit = when (row.state) {
            AddonState.NOT_INSTALLED, AddonState.UPDATE_AVAILABLE -> { row.busy = "Installing..."; { AddonStore.install(row.addon) } }
            AddonState.INSTALLED -> { row.busy = "Removing..."; { AddonStore.uninstall(id) } }
            AddonState.DISABLED -> { row.busy = "Enabling..."; { AddonStore.setEnabled(id, true) } }
            AddonState.RESTART_PENDING -> return
        }
        AddonStore.async(task) {
            row.busy = null
            row.state = AddonStore.stateFor(row.addon)
            row.warning = warningFor(row.addon)
            row.detailsH = -1f
            row.meta = metaText(row)
            row.metaW = -1f
        }
    }

    private fun hover(x: Float, y: Float, w: Float, h: Float): Boolean =
        mx >= x && mx <= x + w && my >= y && my <= y + h

    private fun menuHovering(heights: FloatArray, list: List<Row>, listX: Float, listY: Float, listW: Float): Boolean {
        val p = panelCache
        val listTop = p.y + PAD + HEADER_H
        val listH = p.y + p.h - FOOTER_H - listTop
        var ry = listY - scroll.scrollOffset
        for (i in list.indices) {
            if (list[i].menuOpen) { val m = menuBounds(ry, listX, listW, listTop, listH); return hover(m.x, m.y, m.w, m.h) }
            ry += heights[i] + GAP
        }
        return false
    }

    private fun panelRectFor(sw: Float, sh: Float): Rect {
        val w = 560f.coerceAtMost(sw - 40f).coerceAtLeast(360f)
        val h = 420f.coerceAtMost(sh - 40f).coerceAtLeast(260f)
        return Rect(0f, 0f, w, h)
    }

    private fun panel(): Rect {
        val sw = windowWidth
        val sh = windowHeight
        val r = panelRectFor(sw, sh)
        val maxX = (sw - r.w).coerceAtLeast(0f)
        val maxY = (sh - r.h).coerceAtLeast(0f)
        if (panelX.isNaN()) panelX = maxX / 2f
        if (panelY.isNaN()) panelY = maxY / 2f
        panelX = panelX.coerceIn(0f, maxX)
        panelY = panelY.coerceIn(0f, maxY)
        return Rect(panelX, panelY, r.w, r.h)
    }

    private fun savePanelPos() {
        val sw = windowWidth
        val sh = windowHeight
        val p = panel()
        val maxX = (sw - p.w).coerceAtLeast(1f)
        val maxY = (sh - p.h).coerceAtLeast(1f)
        StoreUiConfig.savePanelPos(p.x / maxX, p.y / maxY)
    }

    private fun detailsH(row: Row, w: Float): Float {
        if (row.detailsW == w && row.detailsH >= 0f) return row.detailsH
        val desc = row.addon.description?.takeIf { it.isNotBlank() } ?: "No description provided."
        var h = SkiaRenderer.wrappedTextHeight(SkiaRenderer.regularFont, desc, w - 28f, 11.5f, 1.35f) + 18f
        if (!row.addon.latest?.cobaltVersion.isNullOrBlank()) h += 15f
        if (!row.addon.repo.isNullOrBlank()) h += 15f
        row.warning?.let { if (showCompatibilityWarnings) h += 2f + SkiaRenderer.wrappedTextHeight(SkiaRenderer.regularFont, it, w - 28f, 10.5f, 1.3f) + 4f }
        row.detailsW = w
        row.detailsH = h.coerceAtLeast(34f) + 12f
        return row.detailsH
    }

    private fun rowH(row: Row): Float {
        if (row.expand <= 0f && !row.targetExpand) return ROW_H
        return ROW_H + detailsH(row, panelCache.w - PAD * 2) * row.expand
    }

    private fun drawExpandTab(x: Float, y: Float, w: Float, h: Float, row: Row) {
        val tabW = 34f
        val tabH = 12f
        val tx = x + (w - tabW) / 2f
        val ty = y + h - tabH
        val hover = hover(tx, ty - 2f, tabW, tabH + 2f)
        val c = if (hover || row.targetExpand) theme.textSecondary else theme.textMuted
        SkiaRenderer.line(tx, ty + tabH, tx + 6f, ty, 1f, theme.border.alpha(120))
        SkiaRenderer.line(tx + tabW, ty + tabH, tx + tabW - 6f, ty, 1f, theme.border.alpha(120))
        SkiaRenderer.line(tx + 6f, ty, tx + tabW - 6f, ty, 1f, theme.border.alpha(120))
        val cx = tx + tabW / 2f
        val cy = ty + 6f
        val p = row.expand
        val up = 1f - p
        SkiaRenderer.line(cx - 5f, cy + 2f * p - 1f * up, cx, cy - 2f * p + 1f * up, 1.4f, c)
        SkiaRenderer.line(cx, cy - 2f * p + 1f * up, cx + 5f, cy + 2f * p - 1f * up, 1.4f, c)
    }

    private fun warningFor(addon: RegistryAddon): String? =
        (AddonStore.compatibilityStatus(addon) as? AddonStore.CompatibilityStatus.Warning)
            ?.takeUnless { it.reason == AddonStore.WarningReason.UNKNOWN_COBALT }?.message

    private fun actionLabel(state: AddonState) = when (state) {
        AddonState.NOT_INSTALLED -> "Install"
        AddonState.INSTALLED -> "Uninstall"
        AddonState.UPDATE_AVAILABLE -> "Update"
        AddonState.DISABLED -> "Enable"
        AddonState.RESTART_PENDING -> "Pending"
    }

    private fun stateLabel(state: AddonState) = when (state) {
        AddonState.NOT_INSTALLED -> "Not installed"
        AddonState.INSTALLED -> "Installed"
        AddonState.UPDATE_AVAILABLE -> "Update available"
        AddonState.DISABLED -> "Disabled"
        AddonState.RESTART_PENDING -> "Restart pending"
    }

    private fun stateColor(state: AddonState): Color = when (state) {
        AddonState.INSTALLED -> theme.success
        AddonState.UPDATE_AVAILABLE -> theme.info
        AddonState.DISABLED -> theme.textMuted
        AddonState.RESTART_PENDING -> theme.warning
        AddonState.NOT_INSTALLED -> theme.textSecondary
    }

    private fun metaText(row: Row): String =
        listOfNotNull(row.addon.author?.takeIf { it.isNotBlank() }?.let { "by $it" }, stateLabel(row.state)).joinToString("  •  ")

    private fun centered(msg: String, p: Rect, color: Color) {
        val lines = SkiaRenderer.wrap(SkiaRenderer.regularFont, msg, p.w - PAD * 2, 13f)
        var y = p.y + (p.h - lines.size * 18f) / 2f
        for (line in lines) {
            SkiaRenderer.text(SkiaRenderer.regularFont, line, p.x + (p.w - SkiaRenderer.textWidth(SkiaRenderer.regularFont, line, 13f)) / 2f, y, 13f, color)
            y += 18f
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            val uri = URI(url)
            val os = System.getProperty("os.name").lowercase()
            val tried = when {
                os.contains("win") -> runCatching {
                    ProcessBuilder("cmd", "/c", "start", "", url).start()
                }.isSuccess
                os.contains("mac") -> runCatching { ProcessBuilder("open", url).start() }.isSuccess
                else -> runCatching { ProcessBuilder("xdg-open", url).start() }.isSuccess
            }
            if (!tried && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
            }
        }.onFailure { it.printStackTrace() }
    }

    private fun ellipsize(text: String, maxWidth: Float, size: Float): String {
        if (SkiaRenderer.textWidth(SkiaRenderer.regularFont, text, size) <= maxWidth) return text
        var end = text.length
        while (end > 0) {
            val s = text.take(end).trimEnd() + "â€¦"
            if (SkiaRenderer.textWidth(SkiaRenderer.regularFont, s, size) <= maxWidth) return s
            end--
        }
        return "â€¦"
    }

    private fun Color.alpha(a: Int) = Color(red, green, blue, a.coerceIn(0, 255))

    private data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

    private companion object {
        const val PAD = 20f
        const val HEADER_H = 58f
        const val FOOTER_H = 44f
        const val ROW_H = 56f
        const val GAP = 8f
    }
}
