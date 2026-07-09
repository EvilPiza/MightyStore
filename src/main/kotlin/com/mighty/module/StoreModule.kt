package com.mighty.module

import org.cobalt.module.Module
import org.cobalt.module.ModuleCategory
import org.cobalt.ui.component.setting.impl.CheckboxSetting

object StoreModule : Module(
    name = "Mighty Store",
    category = ModuleCategory.VISUAL,
    toggleable = false
) {
    val showCompatibilityWarnings by CheckboxSetting(
        name = "Show Warning Message",
        description = "Shows the exact warning for potential incompatibility",
        defaultValue = true
    )
}