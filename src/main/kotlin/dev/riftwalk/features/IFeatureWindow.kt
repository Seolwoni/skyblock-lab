package dev.riftwalk.features

import dev.riftwalk.ui.clickgui.components.FeatureConfigWindow

interface IFeatureWindow {
    fun Feature.createWindow(): FeatureConfigWindow = CBW(this)

    class CBW(feature: Feature): FeatureConfigWindow(feature) {
        override val useSidebar = true
    }
}