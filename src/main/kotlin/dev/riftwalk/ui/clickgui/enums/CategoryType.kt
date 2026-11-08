package dev.riftwalk.ui.clickgui.enums

/**
 * Menu tabs. Floor 7 and Routes are groups inside Dungeons (see Feature.group), not tabs of their own.
 * Mining and Farming are reserved: a tab with no features in it is not shown.
 */
enum class CategoryType(val title: String) {
    GENERAL("General"),
    DUNGEON("Dungeons"),
    FISHING("Fishing"),
    MINING("Mining"),
    FARMING("Farming"),
    VISUAL("Visual"),
    MISC("Misc"),
    DEV("Dev");
}
