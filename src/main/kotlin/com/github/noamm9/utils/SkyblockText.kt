package com.github.noamm9.utils

/**
 * Every server text and item ID the automation reads, in one place. A private server that words something
 * differently (or uses other item IDs) only needs this file changed. Matching is case-insensitive where it says so.
 */
object SkyblockText {
    // ---- fishing: what floats over the water
    /** Over the bobber on a bite (armor stand name or text display). */
    const val BITE = "!!!"
    /** The floating label of a fishing hotspot (case-insensitive). */
    const val HOTSPOT = "HOTSPOT"

    // ---- fishing: chat / lore
    const val DOUBLE_HOOK = "Double Hook"
    val TROPHY_CATCH = Regex("TROPHY FISH! You caught an? (.+?) (BRONZE|SILVER|GOLD|DIAMOND)\\.?$")
    val BAIT_REMAINING = Regex("Bait Remaining: ([\\d,]+)")
    /** "[Lv45] Sea Walker 1,200/1,200❤" -> group 1 = "Sea Walker". */
    val MOB_TAG = Regex("^(?:\\[Lv\\d+] )?(.+?) [\\d,.]+[kKmMbB]?(?:/[\\d,.]+[kKmMbB]?)?❤")
    /** "1,200/1,200❤" -> current, max. */
    val MOB_HEALTH = Regex("([\\d,.]+[kKmMbB]?)/([\\d,.]+[kKmMbB]?)❤")
    /** A creature already at 0 health (it lingers a moment). */
    val MOB_DEAD = Regex("(?:^|\\s)0(?:/[\\d,.]+[kKmMbB]?)?❤")

    /**
     * Sea creature catch line -> the creature (its name plate name). Only the catcher gets the line, so it
     * proves the creature is ours even when the line doesn't name it ("What is this creature!?" = Yeti).
     * Lines from the CC0 skyblock-autofisher mod; matched with "contains" after colour codes are stripped.
     */
    val CATCH_LINES: Map<String, String> = linkedMapOf(
        "A Squid appeared." to "Squid",
        "You caught a Sea Walker." to "Sea Walker",
        "You stumbled upon a Sea Guardian." to "Sea Guardian",
        "It looks like you've disrupted the Sea Witch's brewing session. Watch out, she's furious!" to "Sea Witch",
        "You reeled in a Sea Archer." to "Sea Archer",
        "The Rider of the Deep has emerged." to "Rider of the Deep",
        "Huh? A Catfish!" to "Catfish",
        "Is this even a fish? It's the Carrot King!" to "Carrot King",
        "Your Chumcap Bucket trembles, it's an Agarimoo." to "Agarimoo",
        "Gross! A Sea Leech!" to "Sea Leech",
        "You've discovered a Guardian Defender of the sea." to "Guardian Defender",
        "You have awoken the Deep Sea Protector, prepare for a battle!" to "Deep Sea Protector",
        "The Water Hydra has come to test your strength." to "Water Hydra",
        "The Loch Emperor arises from the depths." to "Loch Emperor",
        "Is it a frog? Is it a man? Well, yes, sorta, IT'S FROG MAN!!!!!!" to "Frog Man",
        "A Snapping Turtle is coming your way, and it's ANGRY!" to "Snapping Turtle",
        "A garish set of tentacles arise. It's a Blue Ringed Octopus!" to "Blue Ringed Octopus",
        "The water bubbles and froths. A massive form emerges- you have disturbed the Wiki Tiki! You shall pay the price." to "Wiki Tiki",
        "An Oasis Rabbit appears from the water." to "Oasis Rabbit",
        "An Oasis Sheep appears from the water." to "Oasis Sheep",
        "A Water Worm surfaces!" to "Water Worm",
        "A Poisoned Water Worm surfaces!" to "Poisoned Water Worm",
        "An Abyssal Miner breaks out of the water!" to "Abyssal Miner",
        "A leech of the mines surfaces... you've caught a Mithril Grubber." to "Mithril Grubber",
        "A leech of the mines surfaces... you've caught a Medium Mithril Grubber." to "Medium Mithril Grubber",
        "A leech of the mines surfaces... you've caught a Large Mithril Grubber." to "Large Mithril Grubber",
        "A leech of the mines surfaces... you've caught a Bloated Mithril Grubber." to "Bloated Mithril Grubber",
        "A Dumpster Diver has emerged from the swamp!" to "Dumpster Diver",
        "The Trash Gobbler is hungry for you!" to "Trash Gobbler",
        "The desolate wail of a Banshee breaks the silence." to "Banshee",
        "A swampy mass of slime emerges, the Bayou Sludge!" to "Bayou Sludge",
        "A long snout breaks the surface of the water. It's an Alligator!" to "Alligator",
        "A massive Titanoboa surfaces. It's body stretches as far as the eye can see." to "Titanoboa",
        "Phew! It's only a Scarecrow." to "Scarecrow",
        "You hear trotting from beneath the waves, you caught a Nightmare." to "Nightmare",
        "It must be a full moon, a Werewolf appears." to "Werewolf",
        "The spirit of a long lost Phantom Fisher has come to haunt you." to "Phantom Fisher",
        "This can't be! The manifestation of death himself!" to "Grim Reaper",
        "Frozen Steve fell into the pond long ago, never to resurface...until now!" to "Frozen Steve",
        "It's a snowman! He looks harmless." to "Frosty",
        "The Grinch stole Jerry's Gifts...get them back!" to "Grinch",
        "What is this creature!?" to "Yeti",
        "You found a forgotten Nutcracker laying beneath the ice." to "Nutcracker",
        "A Reindrake forms from the depths." to "Reindrake",
        "A tiny fin emerges from the water, you've caught a Nurse Shark." to "Nurse Shark",
        "You spot a fin as blue as the water it came from, it's a Blue Shark." to "Blue Shark",
        "A striped beast bounds from the depths, the wild Tiger Shark!" to "Tiger Shark",
        "Hide no longer, a Great White Shark has tracked your scent and thirsts for your blood!" to "Great White Shark",
        "A Flaming Worm surfaces from the depths!" to "Flaming Worm",
        "A Lava Blaze has surfaced from the depths!" to "Lava Blaze",
        "A Lava Pigman arose from the depths!" to "Lava Pigman",
        "Smells of burning. Must be a Fried Chicken." to "Fried Chicken",
        "Trouble's brewing, it's a Fireproof Witch!" to "Fireproof Witch"
    )

    /** The creature a chat message announces as our catch, or null. Longest line first, so specific lines win. */
    fun creatureFromCatch(message: String): String? {
        val m = message.replace(Regex("§."), "").lowercase()
        return CATCH_LINES.entries.sortedByDescending { it.key.length }.firstOrNull { m.contains(it.key.lowercase()) }?.value
    }

    /** The marker Hypixel floats over a waiting bobber ("?" or a countdown like "1.2"): the hook is in the water. */
    val WAITING_MARKER = Regex("^(?:\\?|\\d+(?:\\.\\d+)?s?)$")
    const val REBOOT = "This server will restart soon"
    /** Area name (tab list) -> the warp back there. */
    val WARPS: Map<String, String> = linkedMapOf(
        "Crimson Isle" to "warp crimson", "Backwater Bayou" to "warp bayou", "Jerry" to "warp jerry",
        "Spider's Den" to "warp spider", "The Park" to "warp park", "Galatea" to "warp galatea",
        "Crystal Hollows" to "warp ch", "Dwarven Mines" to "warp mines", "Hub" to "warp hub", "Private Island" to "warp home"
    )
    /** The Golden Fish (lava trophy fish that shows up after ~15 min of fishing): its chat line / floating name. */
    const val GOLDEN_FISH = "Golden Fish"

    // ---- mana
    const val NOT_ENOUGH_MANA_BAR = "NOT ENOUGH MANA"
    const val NOT_ENOUGH_MANA_CHAT = "do not have enough mana"

    // ---- item IDs
    val TELEPORT_SWORDS = setOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END")
    val WITHER_BLADES = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
    const val SOUL_WHIP = "SOUL_WHIP"
    const val FLAMING_FLAY = "FLAMING_FLAY"
    const val HOTSPOT_RADAR = "HOTSPOT_RADAR"
    const val HOTSPOT_RADAR_NAME = "Hotspot Radar"
}
