package dev.marceline.features

import dev.marceline.ui.clickgui.components.FeatureConfigWindow

interface IFeatureWindow {
    fun Feature.createWindow(): FeatureConfigWindow = CBW(this)

    class CBW(feature: Feature): FeatureConfigWindow(feature) {
        override val useSidebar = true
    }
}