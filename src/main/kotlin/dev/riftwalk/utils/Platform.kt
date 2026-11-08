package dev.riftwalk.utils

/** Where the game runs: Android launchers (PojavLauncher, Zalith, FoldCraft...) set tell-tale properties / paths. */
object Platform {
    val isMobile: Boolean by lazy {
        runCatching {
            val env = System.getenv()
            val props = System.getProperties()
            env.keys.any { it.startsWith("POJAV") || it.startsWith("FCL") || it.startsWith("ZALITH") } ||
                env["ANDROID_ROOT"] != null || env["ANDROID_DATA"] != null ||
                props.getProperty("java.vendor", "").contains("Android", true) ||
                props.getProperty("user.home", "").contains("/Android/data/") ||
                props.getProperty("user.dir", "").let { it.contains("/storage/emulated/") || it.contains("/Android/data/") }
        }.getOrDefault(false)
    }
}
