package dev.marceline.features

/**
 * Descriptions for features that came without one (or whose description just repeated the name).
 * Keyed by the feature object's simple class name. Only used when the feature's own text is missing or weak.
 */
internal object FeatureDescriptions {
    private val map = mapOf(
        // general
        "TermAutoClicker" to "Clicks terminals for you at a set speed, with an option to pause while holding Rend.",
        "ItemRarityFeature" to "Paints a rarity-coloured background behind items in your inventory and hotbar.",
        "ChatFeatures" to "Chat quality of life: Ctrl+Click to copy messages, hide spam, emoji shortcuts and auto dialogue.",
        // dungeon
        "ArchitectDraft" to "Announces when you use an Architect's First Draft and can grab one automatically after a failed puzzle.",
        "AutoRequeue" to "Starts the next run automatically after a dungeon ends, optionally only with a full party.",
        "Secrets" to "Highlights secrets you click, plays a sound on pickup and shows a secret counter HUD.",
        "F4Features" to "Thorn helpers: ESP for Thorn, the Spirit Bow and Spirit Bears, plus a bear spawn HUD.",
        "DoorESP" to "Highlights every door in the dungeon run during clear.",
        "PartyFinder" to "Shows joiners' stats and missing classes in Party Finder, with optional auto-kick rules.",
        "BossBarHealth" to "Shows the real health number on boss bars (Watcher, Thorn, F7 Withers).",
        "BloodCamp" to "Blood room helper: predicts where blood mobs will land and times the camp.",
        "Ragnarock" to "Alerts you when to cast the Ragnarock Axe and when its buff runs out.",
        "PuzzleSolvers" to "Solvers for every dungeon puzzle: Blaze, Boulder, Three Weirdos, Creeper Beams, Tic Tac Toe and more.",
        "LividSolver" to "Finds the real Livid in F5/M5, with HP, tracer and an Ice Spray timer.",
        "DungeonMap" to "Live dungeon map with room names, checkmarks, secrets and player heads.",
        // floor 7
        "M7Relics" to "Relic helpers for M7: boxes, spawn and place timers, and a guard against placing the wrong relic.",
        "MelodyAlert" to "Tells your party when you are on the Melody terminal and how far along you are.",
        "F7Titles" to "Big on-screen titles for F7 events: crystals, withers, terminals, gates and lightning.",
        "SimonSays" to "Shows the Simon Says device order and blocks wrong clicks.",
        "ArrowAlign" to "Solves the Arrow Align device, showing how many clicks each frame needs.",
        "AutoI4" to "Fully automates the 4th device (I4) on F7/M7.",
        "I4Helper" to "Highlights the target and predicts the next block on the 4th device (I4).",
        // visual
        "PetDisplay" to "Shows your active pet on screen and highlights it in the pets menu.",
        "PlayerHud" to "Hides vanilla HUD parts you do not need: food, health or armor bars.",
        "DarkMode" to "Darkens the screen with an adjustable tint, optionally over the HUD too.",
        // misc
        "TimeChanger" to "Locks the world to the time of day you pick (client side only).",
        "Camera" to "Camera tweaks: full bright, no front view, camera clip and custom third-person distance.",
        "BlockOverlay" to "Replaces the block outline with a custom fill and outline colour.",
        "NameTagTweaks" to "Nametag tweaks: show your own, remove the background, add a shadow.",
        "ArrowHitSound" to "Replaces the arrow hit sound with one you pick.",
        // dev
        "Timer" to "Tick timer for testing splits (starts on Necron's death, or with /timer start).",
        "WebSocketFeature" to "Connection used for party features shared between Marceline users.",
        "Cosmetics" to "Custom names, badges, sizes, halos and wings for players.",
    )

    fun fallback(feature: Any, name: String, given: String?): String? {
        val g = given?.trim().orEmpty()
        val weak = g.isEmpty() || g.length < 25 || g.replace(" ", "").equals(name.replace(" ", ""), ignoreCase = true)
            || g.replace(" ", "").lowercase().removeSuffix("solver").removeSuffix(".") == name.replace(" ", "").lowercase()
        if (! weak) return given
        return map[feature::class.java.simpleName] ?: given
    }
}
