package dev.marceline.features.impl.fishing

//#if CHEAT
import dev.marceline.config.types.DropdownSetting
import dev.marceline.config.types.KeybindSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.TextInputSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.MainThreadPacketReceivedEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.features.Feature
import dev.marceline.features.impl.routes.AssistBridge
import dev.marceline.features.impl.routes.AutoRoutes
import dev.marceline.ui.notification.NotificationManager
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import dev.marceline.utils.ChatUtils.modMessage
import dev.marceline.utils.MathUtils
import dev.marceline.utils.PlayerUtils
import dev.marceline.utils.GuiUtils
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.items.ItemUtils.skyblockId
import dev.marceline.utils.render.Render2D.drawString
import net.minecraft.client.player.LocalPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.Random
import kotlin.math.abs

/**
 * Fishes by itself in water or lava, with Reel Assist's feel: delays that cluster low with the odd slow reaction,
 * small eased head movements around the bobber every 12-18 s, every turn eased (Assist Core's when installed),
 * and following your aim if you move the camera yourself. Sea creatures are killed as soon as they come up.
 *
 * This file holds the settings, event wiring and start / stop. The rest lives next to it:
 * AutoFishingState (the states), AutoFishingStates (the tick and one handler per state),
 * AutoFishingTransitions (every state change, as data), FishingVars (run state),
 * AutoFishingCast (throw solver, reading the hook, timing), AutoFishingCombat (sea creatures, fights, tracking),
 * AutoFishingTravel (Hotspots, walking, hops), AutoFishingSafety (block centre, lava), AutoFishingSell
 * and AutoFishingSession (reboot resume, reports, the status card).
 */
object AutoFishing: Feature("Fishes by itself in water or lava: casts, reels in on the bite, recasts and kills sea creatures as soon as they come up. Human-like delays and smooth head movement (Reel Assist style)."), FishingVars by FishingVarsImpl() {
    internal val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Look at the water or lava and press it to start, press again to stop")
    internal val fluid by DropdownSetting("Fluid", 0, listOf("Auto", "Water", "Lava")).withDescription("Auto: works out water or lava from where the hook lands. Only used to tell a hook that fell on the ground")

    internal val reelMin by SliderSetting("Reel Delay Min", 2, 1, 17, 1, " ticks").section("Timing").withDescription("Shortest wait after the bite before reeling in")
    internal val reelMax by SliderSetting("Reel Delay Max", 9, 1, 17, 1, " ticks").withDescription("Longest wait after the bite (17 at most, so the fish doesn't get away)")
    internal val castMin by SliderSetting("Cast Delay Min", 6, 1, 60, 1, " ticks").withDescription("Shortest wait before casting again")
    internal val castMax by SliderSetting("Cast Delay Max", 34, 1, 80, 1, " ticks")
    internal val hesitate by SliderSetting("Hesitation", 12, 0, 40, 1, "%").withDescription("Chance that a cast comes a little later than usual (0.3-1 s more)")
    internal val aimWobble by SliderSetting("Cast Aim Wobble", 0.5, 0.0, 2.0, 0.1, "°").withDescription("Spread (sigma) of the left/right aim offset from cast to cast")
    internal val timingModel by DropdownSetting("Timing Model", 2, listOf("Even", "Reel Assist", "Human")).withDescription(
        "Even: any tick in the range equally. Reel Assist: mostly the lower third with the odd slow reaction. Human: skewed reaction times, a rhythm that drifts over minutes, each delay leaning on the last one, slight fatigue over time, no tick count twice in a row"
    )
    internal val slipChance by SliderSetting("Slow Reactions", 6, 0, 30, 1, "%").withDescription("Human model: how often a clearly slow reaction happens").showIf { timingModel.value == 2 }
    internal val drift by SliderSetting("Rhythm Drift", 20, 0, 50, 1, "%").withDescription("Human model: how far the overall speed wanders over minutes").showIf { timingModel.value == 2 }
    internal val fatigue by SliderSetting("Fatigue", 8, 0, 40, 1, "%/h").withDescription("Human model: how much slower reactions get per hour of fishing (up to 2 hours)").showIf { timingModel.value == 2 }
    internal val lookAtCatch by ToggleSetting("Look At The Catch", true).withDescription("After a sea creature or a good catch, waits a bit longer before casting again, like reading what came up")
    internal val breakChance by SliderSetting("Short Breaks", 2.0, 0.0, 15.0, 0.5, "%").withDescription("Chance per cast of a 2-6 s pause before casting (checking chat, the inventory...)")
    internal val castTimeout by SliderSetting("Recast After", 45, 10, 120, 5, " s").withDescription("Reels in and casts again if nothing bites for this long")
    internal val driftRecast by ToggleSetting("Recast If Hook Drifts", true).withDescription("Once the hook has settled, if it slides off (it caught on a mob), reel in and cast again")
    internal val particleRecast by ToggleSetting("Recast On Splash Particles", true).withDescription("A burst of dust particles at the hook means it didn't land in the water: reel in and cast again")
    internal val slugfish by ToggleSetting("Slugfish Mode", false).withDescription("Ignores bites for the first 21 s after a cast (Slugfish only bite after 20 s)")
    internal val slugPet by ToggleSetting("Slug Pet", false).withDescription("With the Slug pet the wait is halved (11 s)").showIf { slugfish.value }
    internal val groundRecast by ToggleSetting("Recast If Hook Lands On Ground", true).withDescription("Casts again when the hook settles somewhere that isn't water / lava; stops after 3 misses in a row")
    internal val hookDive by ToggleSetting("Detect By Hook Dive", true).withDescription("Also reels in when the hook suddenly dives (vanilla bite), not only on the \"!!!\" sign")
    internal val reelSpread by SliderSetting("Reel Spread", 0.9, 0.3, 1.5, 0.05).section("Mistakes").withDescription("Log-normal sigma of the reel delay (Human timing model): higher = more clearly slow reels within the reel range")
    internal val castPitchSpread by SliderSetting("Cast Pitch Spread", 0.6, 0.0, 3.0, 0.1, "°").withDescription("How much the up/down aim of a cast differs from cast to cast")
    internal val castCarry by SliderSetting("Cast Carry-Over", 0.3, 0.0, 0.9, 0.05).withDescription("How much each cast's aim offset leans on the last one")
    internal val castFail by SliderSetting("Cast Fail", 1.5, 0.0, 10.0, 0.5, "%").withDescription("How often a cast comes out clearly off (the hand slips); a cast that misses gets recast")
    internal val lateReel by SliderSetting("Late Reel", 2.0, 0.0, 10.0, 0.5, "%").withDescription("How often the reel comes late after a bite (sometimes late enough to lose the fish)")
    internal val lateReelTicks by SliderSetting("Late Reel By", 14, 2, 30, 1, " ticks").showIf { lateReel.value > 0 }.withDescription("How much later, at most (half to all of this)")
    internal val zoneOut by SliderSetting("Zone Out", 3.0, 0.0, 15.0, 0.5, "%").withDescription("Chance per cast of staring for a moment before casting again")
    internal val zoneOutMin by SliderSetting("Zone Out Min", 3.0, 1.0, 20.0, 0.5, " s").showIf { zoneOut.value > 0 }
    internal val zoneOutMax by SliderSetting("Zone Out Max", 8.0, 1.0, 30.0, 0.5, " s").showIf { zoneOut.value > 0 }

    internal val lookJitter by ToggleSetting("Look Movement", true).section("Look").withDescription("Small eased head movements around the bobber now and then, like a person holding the rod")
    internal val jitterDegrees by SliderSetting("Movement Size", 1.0, 0.2, 4.0, 0.1, "°").withDescription("Size of each small movement (a step of the random walk)")
    internal val driftRange by SliderSetting("Drift Range", 3.0, 0.5, 8.0, 0.5, "°").withDescription("The aim wanders in a random walk but never further than this from where you fish")
    internal val jitterEvery by SliderSetting("Movement Every", 15, 3, 60, 1, " s").withDescription("On average; each wait is 80-120% of this")
    internal val smoothTurns by ToggleSetting("Smooth Turning", true).withDescription("Eases every turn (to sea creatures and back). Uses Assist Core's turning when it's installed")

    internal val rodSlot by SliderSetting("Rod Slot", 0, 0, 9, 1).section("Items").withDescription("Hotbar slot (1-9) of the rod. 0 = first fishing rod in the hotbar")
    internal val killCreatures by ToggleSetting("Kill Sea Creatures", true).section("Sea Creatures")
    internal val killRange by SliderSetting("Detect Range", 14.0, 4.0, 24.0, 0.5, " blocks").withDescription("A sea creature that comes up within this range is killed right away")
    internal val blastRange by SliderSetting("Hyperion Reach", 6.0, 3.0, 10.0, 0.5, " blocks").withDescription("Casts once a creature is this close (the blast hits around you)")
    internal val onlyMine by ToggleSetting("Only My Sea Creatures", true).withDescription(
        "Only fights creatures that came up at your hook right after you reeled in (and anything that hits you). Other people's creatures are left alone"
    )
    internal val creatureCap by SliderSetting("Sea Creature Cap", 10, 0, 30, 1).withDescription("Stops casting while this many of your sea creatures are alive (no new ones come up past the cap anyway). 0 = off")
    internal val rareAlert by ToggleSetting("Rare Creature Alert", true).withDescription("Title, sound and a gold highlight when one of the creatures in the list below comes up for you")
    internal val rareList by dev.marceline.config.types.TextInputSetting("Rare Creatures", "Thunder|Lord Jawbus|Water Hydra|Sea Emperor|Loch Emperor|Great White Shark|Phantom Fisher|Grim Reaper|Yeti|Reindrake|Titanoboa|Plhlegblast|Fiery Scuttler|Ragnarok|Abyssal Miner|Wiki Tiki|Carrot King|Nessie").withDescription("Names, separated by |")
    internal val goldenFish by DropdownSetting("On Golden Fish", 1, listOf("Ignore", "Alert", "Alert and pause")).withDescription(
        "When a Golden Fish shows up (chat line or its name nearby): a loud alert, and optionally reel in and pause so you can catch it yourself. It stays a few minutes as long as you keep fishing near it"
    )
    internal val useCatchMsgs by ToggleSetting("Use Catch Messages", true).withDescription("A creature named in your own catch message is yours for sure; once the server sends these, only they count (other people's creatures never message you)")
    internal val ownWindow by SliderSetting("My Creature Window", 1.0, 0.3, 3.0, 0.1, " s").withDescription("A creature counts as yours only if it appears within this long after YOU reel in. Lower it if other people's creatures get picked up")
    internal val ownDistance by SliderSetting("My Creature Distance", 4.0, 1.5, 8.0, 0.5, " blocks").withDescription("...and only this close to where your hook was")
    internal val quickKill by ToggleSetting("Quick Kill", true).withDescription(
        "Deals with your creature while it's still flying at you instead of after it lands behind you: a short 0.15-0.4 s reaction, " +
        "no glance first - the Hyperion is out and the view down while it comes, and it fires the moment it's in reach; whips follow it through the air"
    )
    internal val noticeMin by SliderSetting("Notice Delay Min", 8, 0, 40, 1, " ticks").withDescription("How long it takes to notice a sea creature that came up, before reacting")
    internal val noticeMax by SliderSetting("Notice Delay Max", 26, 0, 60, 1, " ticks")
    internal val readyMin by SliderSetting("Own Catch Notice Min", 2, 0, 20, 1, " ticks").withDescription("With Quick Kill: how fast your own just-caught creature is noticed (you're expecting it)")
    internal val readyMax by SliderSetting("Own Catch Notice Max", 6, 0, 30, 1, " ticks")
    internal val maxHeight by SliderSetting("Max Height Above Fluid", 3, 1, 10, 1, " blocks").withDescription("Fishing spots are picked at most this high above the water / lava, so your sea creatures can reach you (and the Hyperion reaches them)")
    internal val maxEdge by SliderSetting("Max Distance From Fluid", 3, 2, 8, 1, " blocks").withDescription("Fishing spots are at most this far from the water / lava edge (lava: never closer than 2)")
    internal val moveDown by ToggleSetting("Move Down To Fish", true).withDescription("Started up high looking at the fluid: walks down to a spot just above it first")
    internal val leaveFightAfter by SliderSetting("Leave Fight After", 6, 2, 40, 1, " ticks").withDescription("Once everything of yours is dead, back to fishing after this long (still 1.5 s while one of yours is alive but out of reach)")
    internal val recastAfterFight by ToggleSetting("Quick Recast After Fight", true).withDescription("No extra 'reading the catch' pause after a fight: cast as soon as the view is back on the spot")
    internal val giveUp by SliderSetting("Give Up After", 5, 1, 30, 1, " s").withDescription("A creature that doesn't come within reach in this time is left alone and fishing goes on")
    internal val manaCost by SliderSetting("Mana Per Cast", 300, 0, 1000, 10).withDescription("Wither Impact's mana cost for you. Below it, the Hyperion swings as a sword instead of casting (no more standing there with no mana)")
    internal val pauseMana by SliderSetting("Pause Below Mana", 35, 0, 90, 5, "%").withDescription("Stops casting the rod while mana is under this, so no new sea creatures come up until it has refilled (0 = never pause)")
    internal val weaponType by DropdownSetting("Weapon", 0, listOf("Hyperion", "Soul Whip", "Flaming Flay")).withDescription(
        "Hyperion: Wither Impact looking down. Soul Whip / Flaming Flay: aims at the creature and right-clicks (Flay), no teleport. Falls back to the Hyperion if the whip isn't in the hotbar"
    )
    internal val whipRange by SliderSetting("Whip Range", 11.0, 4.0, 14.0, 0.5, " blocks").withDescription("How close a creature has to be before the whip is used (Flay reaches about 13 forward)").showIf { weaponType.value != 0 }
    internal val whipDelay by SliderSetting("Whip Delay", 6, 2, 30, 1, " ticks").withDescription("Time between whip swings (a little random on top)").showIf { weaponType.value != 0 }
    internal val weaponSlot by SliderSetting("Weapon Slot", 0, 0, 9, 1).withDescription("Hotbar slot (1-9) of the weapon. 0 = Hyperion / Astraea / Scylla / Valkyrie if found")
    internal val hitDelay by SliderSetting("Hit Delay", 4, 1, 20, 1, " ticks")
    internal val hypeMelee by ToggleSetting("Hyperion Melee", false).withDescription("Without mana for Wither Impact, swing the Hyperion as a sword. Off: never hit with it, wait for mana instead")
    internal val hypeDelay by SliderSetting("Hyperion Cast Delay", 2, 1, 10, 1, " ticks").withDescription("Typical time between Hyperion casts (log-normal around it, Click Spread). 2 = about 8-10 casts a second")
    internal val immuneWindow by SliderSetting("Immunity Check Window", 20, 0, 60, 1, " ticks").withDescription("Only a creature that came up this recently gets the one 'took no damage, wait' pause (0 = never wait)")
    internal val immuneWaitMin by SliderSetting("Immunity Wait Min", 6, 0, 30, 1, " ticks").showIf { immuneWindow.value > 0 }
    internal val immuneWaitMax by SliderSetting("Immunity Wait Max", 10, 0, 40, 1, " ticks").showIf { immuneWindow.value > 0 }

    internal val hotspots by ToggleSetting("Go To Hotspots", false).section("Hotspots").withDescription(
        "Finds the nearest fishing Hotspot, walks to a spot on the shore next to it and casts into it. When it goes away, moves on to the next one. With none around it fishes where you started"
    )
    internal val hopMin by SliderSetting("AOTV Gap Min", 5, 2, 40, 1, " ticks").withDescription("Shortest wait between etherwarps / AOTV hops on the way (same timing model as casting)")
    internal val hopMax by SliderSetting("AOTV Gap Max", 18, 2, 60, 1, " ticks")
    internal val walkSome by SliderSetting("Walk Instead", 15, 0, 80, 5, "%").withDescription("Chance to walk a stretch instead of hopping, when walking is possible")
    internal val aotvRange by SliderSetting("AOTV Distance", 12, 8, 16, 1, " blocks").withDescription("How far your AOTV teleports (8 + Transmission Tuners)")
    internal val useRadar by ToggleSetting("Use Hotspot Radar", true).withDescription("No hotspot in sight or remembered: reads a Hotspot Radar in the hotbar, twice from two places, and goes where the two lines cross")
    internal val readShared by ToggleSetting("Hotspots From Chat", true).withDescription("Remembers hotspot positions people share in chat (any message with \"hotspot\" and x y z)")
    internal val spotSearch by SliderSetting("Find Water Within", 48, 16, 96, 4, " blocks").withDescription("With no hotspot (or hotspots off) and not already looking at water / lava: how far to look for the nearest pool to walk to")
    internal val resumeAfterDeath by ToggleSetting("Come Back After Death", true).withDescription("Dying (\"☠ You ...\" message) doesn't end the session: after the respawn it walks / warps back to the spot and fishes on")
    internal val maxDeaths by SliderSetting("Max Deaths", 3, 1, 10, 1).showIf { resumeAfterDeath.value }.withDescription("Stops for good after this many deaths within 10 minutes (something keeps killing you there)")
    internal val stallSeconds by SliderSetting("Stall Recovery After", 10, 0, 300, 5, " s").withDescription("Nothing has happened for this long (same state, no cast, no move, no kill): writes down why in the log and starts over from finding a spot. Waiting for a bite and breaks don't count. 0 = off")
    internal val resumeAfterReboot by ToggleSetting("Come Back After Reboot", true).withDescription("Only after the server's own restart warning: if you're moved within 3 minutes, warp back to the island and carry on fishing. Any other move still stops")
    internal val warpCommand by dev.marceline.config.types.TextInputSetting("Warp Command", "").withDescription("The command back to your fishing island, without the slash (empty = the usual one, e.g. warp crimson)")
    internal val hotspotRange by SliderSetting("Search Range", 200, 16, 400, 8, " blocks").withDescription("How far it looks for a Hotspot. Hotspots seen earlier are remembered for 3 minutes, even after they're out of sight")
    internal val useEther by ToggleSetting("Etherwarp On The Way", true).withDescription("Plans etherwarps (and AOTV hops) to get to far or high spots, checking where each one lands, like Secret Routes does")
    internal val showRoute by ToggleSetting("Show Route", true).withDescription("Draws the planned walk and the next etherwarp / AOTV landing in the world")
    internal val tpAote by ToggleSetting("Use AOTE", false).section("Teleport").withDescription("Plans blinks with the Aspect of the End on the way to a spot (8 blocks, 50 mana). Only while travelling")
    internal val tpAotv by ToggleSetting("Use AOTV", false).withDescription("Plans blinks with the Aspect of the Void (8 blocks, 45 mana)")
    internal val tpEther by ToggleSetting("Use Etherwarp", false).withDescription("Plans etherwarps (AOTV with Etherwarp Conduit, up to 61 blocks onto a block; needs soulflow, read from the tab list)")
    internal val tpMaxChain by SliderSetting("Max Teleports In A Row", 3, 1, 8, 1).showIf { tpAote.value || tpAotv.value || tpEther.value }
    internal val tpManaReserve by SliderSetting("Mana Reserve", 300, 0, 3000, 50).showIf { tpAote.value || tpAotv.value || tpEther.value }.withDescription("Mana kept for fighting: below it, travel walks")
    internal val tpManaWeight by SliderSetting("Mana Weight", 1.0, 0.0, 5.0, 0.1, "x").showIf { tpAote.value || tpAotv.value || tpEther.value }.withDescription("How much spent mana counts against a teleport, per tick of mana regen it takes back (1 = one tick)")
    internal val tpAimMedian by SliderSetting("Teleport Aim Error", 1.5, 0.0, 5.0, 0.1, "°").showIf { tpAote.value || tpAotv.value || tpEther.value }.withDescription("Typical aim error of a teleport (a fresh one each time); coming down a bit short is normal")
    internal val tpAimSpread by SliderSetting("Teleport Aim Spread", 0.42, 0.0, 1.0, 0.02).showIf { tpAote.value || tpAotv.value || tpEther.value }.withDescription("Log-normal sigma of that error (0.42 with 1.5 gives about 4 degrees at P99)")
    internal val tpGapMedian by SliderSetting("Teleport Gap", 3.0, 1.0, 10.0, 0.5, " ticks").showIf { tpAote.value || tpAotv.value || tpEther.value }.withDescription("Typical gap between two teleports in a row (log-normal, never the same three times)")
    internal val tpGapSpread by SliderSetting("Teleport Gap Spread", 0.5, 0.0, 1.2, 0.05).showIf { tpAote.value || tpAotv.value || tpEther.value }
    internal val tpPauseMin by SliderSetting("Stop Before Min", 1, 0, 5, 1, " ticks").showIf { tpAote.value || tpAotv.value || tpEther.value }.withDescription("Standing still before a teleport")
    internal val tpPauseMax by SliderSetting("Stop Before Max", 2, 0, 8, 1, " ticks").showIf { tpAote.value || tpAotv.value || tpEther.value }
    internal val tpSwapMin by SliderSetting("Item Swap Min", 1, 0, 5, 1, " ticks").showIf { tpAote.value || tpAotv.value || tpEther.value }.withDescription("Delay of the item swap, and of swapping back after landing")
    internal val tpSwapMax by SliderSetting("Item Swap Max", 2, 0, 8, 1, " ticks").showIf { tpAote.value || tpAotv.value || tpEther.value }
    internal val tpHoldMin by SliderSetting("Aim Hold Min", 1, 0, 5, 1, " ticks").showIf { tpAote.value || tpAotv.value || tpEther.value }.withDescription("Waiting on the aim before using the item")
    internal val tpHoldMax by SliderSetting("Aim Hold Max", 2, 0, 8, 1, " ticks").showIf { tpAote.value || tpAotv.value || tpEther.value }
    internal val tpSettleMin by SliderSetting("Settle After Min", 2, 0, 10, 1, " ticks").showIf { tpAote.value || tpAotv.value || tpEther.value }.withDescription("After landing, the view stays put this long before turning to what's next")
    internal val tpSettleMax by SliderSetting("Settle After Max", 5, 0, 15, 1, " ticks").showIf { tpAote.value || tpAotv.value || tpEther.value }
    internal val hotspotRadius by SliderSetting("Hotspot Size", 2.5, 1.0, 6.0, 0.5, " blocks").withDescription("How close to the Hotspot's centre the hook has to land; it adjusts the aim until it does")
    internal val stayHome by ToggleSetting("Return To Spot", true).withDescription("Walks back to the fishing spot if you get pushed away from it")

    internal val autoSell by ToggleSetting("Auto Sell", false).section("Auto Sell").withDescription(
        "When the inventory is nearly full: opens the SkyBlock Menu (hotbar slot below), clicks the Booster Cookie, sells only the items on your sell list to the NPC, closes it and goes back to fishing"
    )
    internal val sellWhenFree by SliderSetting("Sell When Free Slots", 3, 0, 15, 1).withDescription("Sells once this few empty slots are left")
    internal val menuSlot by SliderSetting("Menu Slot", 9, 1, 9, 1).withDescription("Hotbar slot of the SkyBlock Menu (the Nether Star)")
    internal val sellListKey by KeybindSetting("Add To Sell List Key").withDescription("In any inventory, point at an item and press this to add it to (or remove it from) the sell list. Only items on the list are ever sold")
    val sellList by dev.marceline.config.types.TextInputSetting("Sell List", "").withDescription("The items Auto Sell may sell, by name, separated by | (fill it with the key above, or type names here)")
    internal val clearSellList by dev.marceline.config.types.ButtonSetting("Clear Sell List") {
        sellList.value = ""
        NotificationManager.push("Sell List", "&fCleared")
    }.withDescription("Empties the sell list")

    internal val failsafe by ToggleSetting("Stop On Anything Unexpected", true).section("Failsafe").withDescription(
        "Stops (doesn't try to hide anything): when the server teleports or turns you, your view jumps 70 degrees or more in an instant, your held item is changed, or a menu opens by itself"
    )
    internal val stopOnParty by ToggleSetting("Stop On Party Invite", true).showIf { failsafe.value }.withDescription("Someone invites you to a party: stop and tell you")
    internal val stopOnMessage by ToggleSetting("Stop On Private Message", true).showIf { failsafe.value }.withDescription("A \"From <name>:\" message: stop and tell you")
    internal val stopOnStaff by ToggleSetting("Stop On Staff Nearby", true).showIf { failsafe.value }.withDescription("A player with a staff tag in their name is near you: stop and tell you")
    internal val staffTags by TextInputSetting("Staff Tags", "ADMIN, OWNER, MOD, HELPER, GM, YOUTUBE").showIf { failsafe.value && stopOnStaff.value }.withDescription("Tags to look for, comma separated (written without brackets)")
    internal val staffRange by SliderSetting("Staff Range", 48, 8, 128, 4, " blocks").showIf { failsafe.value && stopOnStaff.value }
    internal val stopOnTeleport by ToggleSetting("Stop On Teleport", true).showIf { failsafe.value }.withDescription("You jump a long way in one tick without a warp / AOTV of ours: stop and tell you")
    internal val teleportDistance by SliderSetting("Teleport Distance", 30, 10, 100, 5, " blocks").showIf { failsafe.value && stopOnTeleport.value }
    internal val longBreaks by ToggleSetting("Long Breaks", true).section("Breaks").withDescription("Every so often reels in and takes a proper break, looking around now and then, like a person getting a drink")
    internal val breakEveryMin by SliderSetting("Break Every Min", 25, 5, 120, 1, " min").showIf { longBreaks.value }
    internal val breakEveryMax by SliderSetting("Break Every Max", 45, 5, 180, 1, " min").showIf { longBreaks.value }
    internal val breakLenMin by SliderSetting("Break Length Min", 60, 10, 900, 10, " s").showIf { longBreaks.value }
    internal val breakLenMax by SliderSetting("Break Length Max", 240, 10, 1800, 10, " s").showIf { longBreaks.value }
    internal val showMarkers by ToggleSetting("Show Markers", true).section("Display").withDescription("A box where the hook lands and a highlight on your sea creatures (works with Kill Sea Creatures off too)")
    internal val creatureGlow by ToggleSetting("Creature Outline", true).withDescription("Outlines your sea creatures' actual shape (the game's glow), on top of the box")
    internal val creatureTracer by ToggleSetting("Creature Tracer", true).withDescription("A line from the middle of your screen to each of your sea creatures")

    internal val standCentre by ToggleSetting("Stand In Block Centre", true).section("Safety").withDescription("Before casting and before fighting, steps (sneaking, so it can't walk off an edge) to the exact middle of a safe block: solid ground under it, no lava in or under it")
    internal val discordOn by ToggleSetting("Discord Alerts", false).section("Discord").withDescription(
        "When a session ends, posts one message to your Discord channel: \"goal reached\" or why it stopped, with the session report"
    )
    internal val discordUrl by dev.marceline.config.types.TextInputSetting("Webhook URL", "").withDescription(
        "Channel settings > Integrations > Webhooks > New Webhook > Copy Webhook URL, then paste it here"
    )
    internal val discordTest by dev.marceline.config.types.ButtonSetting("Send Test Message") {
        dev.marceline.utils.DiscordWebhook.send(discordUrl.value, "🎣 Marceline 연결 확인", "웹후크가 잘 연결됐어요. 낚시가 끝나면 여기로 알려드릴게요.", 0x8B5CF6) { ok, why ->
            info(if (ok) "&aDiscord test sent." else "&cDiscord test failed: $why")
        }
    }
    internal val sendLogsButton by dev.marceline.config.types.ButtonSetting("Send Logs To Discord") { sendLogs() }
        .withDescription("Uploads this game's log (session reports included) and the previous one to your Discord webhook - handy on a phone, where the files are hard to reach (needs the Webhook URL above)")
    internal val checkButton by dev.marceline.config.types.ButtonSetting("Fishing Check") { runCatching { fishingCheck() }.onFailure { info("&cCheck failed: ${it.message}") } }
        .section("Server Check").withDescription("Prints what the mod can see right now: hotbar item IDs, floating texts nearby (hotspot / bite / name tags), the hook, tab stats, mana and bait. Stand by the water with the rod out")
    internal val saveLogsButton by dev.marceline.config.types.ButtonSetting("Save Logs To Downloads") { saveLogsToDownloads(true) }
        .withDescription("Copies this game's log (session reports included) and the previous one into Downloads/Marceline/logs, where a file manager can open them (phone or PC). The log is also written there live while you play")
    internal val autoSaveLogs by ToggleSetting("Save Logs After Each Session", dev.marceline.utils.Platform.isMobile)
        .withDescription("Does the same on its own whenever a session ends (on by default on phones)")
    internal val copyLogButton by dev.marceline.config.types.ButtonSetting("Copy Log Path") {
        mc.keyboardHandler.clipboard = journal.path
        info("&7Fishing log: &f${journal.path} &8(copied)")
    }.withDescription("Copies where this game's log is, to send it over after a test")
    internal val stopAfterMin by SliderSetting("Stop After", 0, 0, 600, 10, " min").withDescription("Ends the session on its own after this long (0 = never)")
    internal val stopAfterKills by SliderSetting("Stop After Kills", 0, 0, 5000, 50).withDescription("Ends the session after this many sea creature kills (0 = never)")
    internal val lowHealth by DropdownSetting("On Low Health", 0, listOf("Wait", "Stop")).section("Safety").withDescription("Wait: no new casts until health is back (fights go on, the creatures are what hurts); Stop: end the session. With Come Back After Death on it always waits (a death brings you back)")
    internal val stopHealth by SliderSetting("Low Health", 30, 0, 90, 5, "%").withDescription("Below this, wait (or stop). 0 = never")
    internal val resumeHealth by SliderSetting("Resume At", 70, 10, 100, 5, "%").showIf { lowHealth.value == 0 }.withDescription("Fishing goes on once health is back up to this")
    internal val criticalHealth by SliderSetting("Stop Below (Always)", 15, 0, 50, 5, "%").showIf { lowHealth.value == 0 }.withDescription("Still stops when it gets this low while waiting (0 = never). Not with Come Back After Death on")
    internal val chatInfo by ToggleSetting("Chat Messages", true)
    internal val statusHud by ToggleSetting("Status HUD", true)

    // planned hops (etherwarp / AOTV) while travelling
    class Hop(val ether: Boolean, val slot: Int, val target: BlockPos?, val yaw: Float, val pitch: Float, val landing: Vec3)

    // which creatures are ours: they come up at our hook right after we reel in
    class Reel(val tick: Int, val at: Vec3)

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) mc.player?.let { dev.marceline.utils.Humanize.settle(it) }
            if (running) runCatching(::tick).onFailure { dev.marceline.utils.Safely.report("AutoFishing tick", it); stop("error: ${it.message}") }
        }
        register<dev.marceline.event.impl.ChatMessageEvent> {
            if (! running || ! failsafe.value) return@register
            val t = event.unformattedText
            if (stopOnParty.value) dev.marceline.utils.WatchSigns.partyInvite(t)?.let { return@register failStop("party invite from $it") }
            if (stopOnMessage.value) dev.marceline.utils.WatchSigns.privateMessage(t)?.let { return@register failStop("private message from $it") }
        }
        register<dev.marceline.event.impl.ChatMessageEvent> {
            // SkyBlock deaths: "☠ You were killed by ..." / "☠ You burned to death." - then the server moves you to spawn
            if (! running || ! resumeAfterDeath.value) return@register
            val t = event.unformattedText.trim()
            if (! t.startsWith("☠ You")) return@register
            ownMoveTicks = 200 // the respawn teleport is not a failsafe
            deaths10m.addLast(tickCount)
            while (deaths10m.isNotEmpty() && tickCount - deaths10m.first() > 12_000) deaths10m.removeFirst()
            journal.log("died: $t  (${deaths10m.size} in 10 min)")
            if (deaths10m.size >= maxDeaths.value) return@register failStop("died ${deaths10m.size} times in 10 minutes ($t)")
            statDeaths++
            armReboot() // remember the spot and island, same as a reboot
            beginResume()
            resumeWait = 40 + RANDOM.nextInt(60) // 2-5 s after the respawn
            resumeWhy = "after dying"
        }
        register<dev.marceline.event.impl.ChatMessageEvent> { if (readShared.value) dev.marceline.utils.Safely("AutoFishing shared hotspot") { readSharedHotspot(event.unformattedText) } }
        register<dev.marceline.event.impl.ChatMessageEvent> {
            val t = event.unformattedText.replace(Regex("§."), "")
            if (running && t.contains(dev.marceline.utils.SkyblockText.GOLDEN_FISH, ignoreCase = true) && ! t.contains("TROPHY FISH", true) && ! Regex("^(?:\\[[^]]+] )*[A-Za-z0-9_]{2,16}: ").containsMatchIn(t)) onGoldenFish("chat: ${t.take(60)}")
        }
        register<dev.marceline.event.impl.ChatMessageEvent> {
            if (running && event.unformattedText.contains(dev.marceline.utils.SkyblockText.REBOOT, ignoreCase = true)) {
                armReboot()
                NotificationManager.push("Server restarting", "&fThe server will restart soon - Auto Fishing keeps going until you're moved")
                dev.marceline.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 0.8f)
                journal.log("server reboot warning")
            }
        }
        register<dev.marceline.event.impl.ChatMessageEvent> {
            if (! running) return@register
            val t = event.unformattedText.replace(Regex("§."), "").trim()
            val lastReel = reels.lastOrNull()?.tick ?: return@register
            if (tickCount - lastReel > 30 || t.length > 160) return@register
            if (Regex("^(?:\\[[^]]+] )*[A-Za-z0-9_]{2,16}: ").containsMatchIn(t)) return@register // a player talking
            if (t.contains(dev.marceline.utils.SkyblockText.DOUBLE_HOOK, ignoreCase = true)) { claimsLeft++; return@register }
            // a known catch line names the creature even when the text doesn't ("What is this creature!?" = Yeti)
            val known = dev.marceline.utils.SkyblockText.creatureFromCatch(t)
            catches += tickCount to (t.lowercase() + (known?.let { " | " + it.lowercase() } ?: ""))
            if (known != null) journal.log("catch line: $known")
            claimsLeft++
        }
        register<WorldChangeEvent> {
            remembered.clear(); radarLines.clear(); radarStage = 0
            if (running) { if (rebootWindow()) beginResume() else stop("world changed") }
        }
        register<dev.marceline.event.impl.KeyboardEvent.KeyPressed> {
            if (event.action != com.mojang.blaze3d.platform.InputConstants.PRESS) return@register
            val screen = mc.gui.screen() as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*> ?: return@register
            if (! sellListKey.matches(event.keyEvent.key, mouse = false)) return@register
            val slot = (screen as dev.marceline.mixin.IAbstractContainerScreen).hoveredSlot ?: return@register
            val name = itemKey(slot.item) ?: return@register
            val list = sellNames().toMutableSet()
            val added = list.add(name)
            if (! added) list.remove(name)
            sellList.value = list.joinToString("|")
            dev.marceline.config.ConfigManager.save()
            NotificationManager.push("Sell List", if (added) "&fAdded &d$name" else "&fRemoved &d$name")
            event.isCanceled = true
        }
        register<dev.marceline.event.impl.CheckEntityGlowEvent> {
            if (! running || ! showMarkers.value || ! creatureGlow.value || event.entity.id !in mineAlive) return@register
            event.color = if (event.entity.id in rareIds) java.awt.Color(255, 196, 64) else dev.marceline.ui.clickgui.components.settings.Style.accent2
        }
        register<dev.marceline.event.impl.RenderWorldEvent> { dev.marceline.utils.Safely("AutoFishing frame") { dev.marceline.utils.Humanize.frame(); trackFrame() } }
        register<dev.marceline.event.impl.RenderWorldEvent> {
            if (! running || ! showMarkers.value) return@register
            val level = mc.level ?: return@register
            with(dev.marceline.utils.render.world.Render3D) {
                lastLanding?.takeIf { state != FishingState.TRAVEL }?.let { l ->
                    val c = dev.marceline.ui.clickgui.components.settings.Style.accentColor
                    event.ctx.renderBoxBounds(l.x - 0.3, l.y - 0.1, l.z - 0.3, l.x + 0.3, l.y + 0.5, l.z + 0.3, c, c.withAlpha(70), phase = true, lineWidth = 2)
                }
                val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
                for (id in mineAlive) {
                    val e = level.getEntity(id) ?: continue
                    if (! e.isAlive) continue
                    val c = if (id in rareIds) java.awt.Color(255, 196, 64) else dev.marceline.ui.clickgui.components.settings.Style.accent2
                    // interpolated so the box doesn't lag behind a moving mob
                    val at = e.getPosition(pt)
                    val bb = e.boundingBox.move(at.subtract(e.position())).inflate(0.1)
                    // fades in when it becomes ours; a rare one breathes
                    val age = System.currentTimeMillis() - (claimedAt[id] ?: 0L)
                    val fadeIn = (age / 300f).coerceIn(0f, 1f)
                    val pulse = if (id in rareIds) 0.75f + 0.25f * kotlin.math.sin(age / 180.0).toFloat() else 1f
                    val a = fadeIn * pulse
                    event.ctx.renderBoxBounds(bb, c.withAlpha((255 * a).toInt()), c.withAlpha((40 * a).toInt()), phase = true, lineWidth = 2)
                    if (creatureTracer.value) event.ctx.renderTracer(Vec3(at.x, bb.minY + (bb.maxY - bb.minY) / 2, at.z), c.withAlpha((170 * a).toInt()), 1.2)
                    // label: name, then a health bar and the distance
                    val raw = (tagOf(e) ?: e.name.string).replace(Regex("§."), "")
                    val name = creatureName(e) ?: raw.substringBefore('❤').trim()
                    val hp = dev.marceline.utils.SkyblockText.MOB_HEALTH.find(raw)
                    val frac = hp?.let { (parseShort(it.groupValues[1]) / parseShort(it.groupValues[2]).coerceAtLeast(1.0)).coerceIn(0.0, 1.0) }
                        ?: (e as? LivingEntity)?.let { (it.health / it.maxHealth).toDouble().coerceIn(0.0, 1.0) } ?: 1.0
                    val dist = mc.player?.distanceTo(e)?.toInt() ?: 0
                    val nameCol = if (id in rareIds) "§6§l" else "§f"
                    val barCol = when { frac > 0.6 -> "§a"; frac > 0.3 -> "§e"; else -> "§c" }
                    val filled = (frac * 12).toInt().coerceIn(0, 12)
                    val bar = barCol + "▮".repeat(filled) + "§8" + "▮".repeat(12 - filled)
                    event.ctx.renderString("$nameCol$name", at.x, bb.maxY + 0.85, at.z, java.awt.Color.WHITE, 1.15f, phase = true)
                    // how long it has been alive: it despawns at ~6 min
                    val ageS = (age / 1000).toInt()
                    val ageCol = if (ageS >= 300) "§c" else if (ageS >= 240) "§e" else "§8"
                    event.ctx.renderString("$bar §7${dist}m $ageCol${ageS / 60}:${"%02d".format(ageS % 60)}", at.x, bb.maxY + 0.5, at.z, java.awt.Color.WHITE, 0.9f, phase = true)
                }
            }
        }
        register<dev.marceline.event.impl.RenderWorldEvent> {
            if (! running || ! showRoute.value) return@register
            val p = mc.player ?: return@register
            with(dev.marceline.utils.render.world.Render3D) {
                // where we're fishing / heading: a tall beam you can see from far away, with the distance
                val spot = hotspot?.takeIf { it.isAlive }?.position() ?: stand
                if (spot != null) {
                    val beam = if (hotspot != null) java.awt.Color(232, 80, 106) else java.awt.Color(80, 220, 255)
                    val d = p.position().distanceTo(spot)
                    event.ctx.renderBoxBounds(spot.x - 0.25, spot.y, spot.z - 0.25, spot.x + 0.25, spot.y + 30, spot.z + 0.25, beam.withAlpha(160), beam.withAlpha(55), phase = true, lineWidth = 1)
                    hotspot?.let { event.ctx.renderCircle(Vec3(spot.x, spot.y - 1.5, spot.z), hotspotRadius.value, beam, 2, phase = true) }
                    val label = (if (hotspot != null) "Hotspot" else "Fishing spot") + " §7${d.toInt()}m"
                    event.ctx.renderString(label, spot.x, spot.y + 2.5, spot.z, beam, (1.2 + d / 40).coerceAtMost(4.0), phase = true)
                }
                if (state != FishingState.TRAVEL) return@register
                // the walk: purple fading to pink, with a dot on every turn
                val left = path.size - pathIndex
                var from = p.position().add(0.0, 0.1, 0.0)
                for (i in pathIndex until path.size) {
                    val c = Vec3.atBottomCenterOf(path[i]).add(0.0, 0.1, 0.0)
                    val t = if (left <= 1) 1f else (i - pathIndex).toFloat() / (left - 1)
                    val col = java.awt.Color((167 + (236 - 167) * t).toInt(), (107 + (72 - 107) * t).toInt(), (250 + (153 - 250) * t).toInt())
                    event.ctx.renderLine(from, c, col, 3, phase = true)
                    val turn = i + 1 < path.size && i > 0 && (path[i + 1].x - path[i].x != path[i].x - path[i - 1].x || path[i + 1].z - path[i].z != path[i].z - path[i - 1].z)
                    if (turn || i == path.size - 1) event.ctx.renderBoxBounds(c.x - 0.08, c.y - 0.05, c.z - 0.08, c.x + 0.08, c.y + 0.11, c.z + 0.08, col, col, phase = true, lineWidth = 1)
                    from = c
                }
            }
            hop?.let { h ->
                val col = if (h.ether) java.awt.Color(232, 80, 106) else java.awt.Color(120, 200, 255)
                with(dev.marceline.utils.render.world.Render3D) {
                    event.ctx.renderLine(p.eyePosition, h.landing.add(0.0, 0.2, 0.0), col, 2, phase = true)
                    event.ctx.renderBlock(BlockPos.containing(h.landing.x, h.landing.y - 0.5, h.landing.z), col, fill = false, phase = true, lineWidth = 3)
                }
            }
            stand?.let { with(dev.marceline.utils.render.world.Render3D) { event.ctx.renderBlock(BlockPos.containing(it.x, it.y - 0.5, it.z), java.awt.Color(80, 255, 140), fill = false, phase = true, lineWidth = 2) } }
        }
        register<MainThreadPacketReceivedEvent.Pre> {
            val pk = event.packet
            if (running && radarStage == 1 && pk is net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket) radarParticle(pk.x, pk.y, pk.z)
            if (running && state == FishingState.WAIT_BITE && pk is net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket && pk.particle.type == net.minecraft.core.particles.ParticleTypes.DUST) {
                val h = mc.player?.fishing
                if (h != null && h.position().distanceToSqr(pk.x, pk.y, pk.z) < 1.6 * 1.6) dustAt += tickCount
            }
        }
        register<MainThreadPacketReceivedEvent.Pre> {
            // the server picking the held slot itself (it does after some menus / items): not you changing it
            if (running && event.packet is net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket) serverSlotAt = tickCount
        }
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! running || ! failsafe.value || ownMoveTicks > 0 || state == FishingState.RESUME) return@register
            val name = event.packet.javaClass.simpleName
            if (event.packet is ClientboundPlayerPositionPacket || name.contains("PlayerRotation")) mc.execute { if (running) failStop("the server moved or turned you") }
        }

        hudElement("Auto Fishing Status", { statusHud.value }, { running }) { ctx, example ->
            drawCard(ctx, example)
        }.also { it.themedPanel = false }
    }

    override fun onDisable() {
        super.onDisable()
        if (running) stop(null)
    }

    internal fun info(msg: String) {
        journal.log("info  $msg")
        if (chatInfo.value) modMessage(msg)
    }

    internal fun start() {
        val p = mc.player ?: return
        if (rod() == null) return info("&cNo fishing rod in the hotbar.")
        journal.log("==== start  at ${p.blockPosition().toShortString()}  hotspots=${hotspots.value} weapon=${listOf("Hyperion", "Soul Whip", "Flaming Flay")[weaponType.value]}")
        anchorYaw = p.yRot
        anchorPitch = p.xRot
        seenAt.clear(); seenPos.clear(); reels.clear(); catches.clear(); chase.clear(); claimsLeft = 0
        claimedAt.clear(); despawnWarned.clear(); rareIds.clear(); castHp.clear(); immuneWaited.clear(); deaths.clear()
        tickCount += 100 // anything seen from now on is "after" any stale reel
        timing = dev.marceline.utils.HumanTiming().apply(dev.marceline.utils.Humanize.profile())
        lastLookYaw = null
        lastTickPos = null; lastTickLevel = null; lastTickPlayer = null
        TravelBlink.reset()
        deaths10m.clear(); statDeaths = 0; statStalls = 0; stallSig = ""; stallSince = tickCount; resumeWhy = "after the reboot"
        hypeStage = 0; hypeBeat = 0; castDownPitch = 0f; blastBlocked = false; hypeFrom = null; heightWarned = false; healthWaiting = false
        reelTiming = dev.marceline.utils.HumanTiming().apply(dev.marceline.utils.Humanize.profile())
        castYawSeries = 0.0; castPitchSeries = 0.0; lastClickGap = - 1
        wobYaw = 0f; wobPitch = 0f
        aimTarget = null
        noticeAt.clear()
        path = emptyList()
        sellFails = 0
        sold = 0
        sellRefused.clear(); travelGiveUps = 0
        reels.clear()
        mine.clear()
        mineAlive.clear()
        kills = 0
        creaturesCaught = 0
        sessionStart = System.currentTimeMillis()
        lastLanding = null
        if (longBreaks.value) scheduleBreak() else nextBreakAt = 0L
        firstSeen.clear()
        mc.level?.let { l -> l.entitiesForRendering().forEach { firstSeen += it.id } } // everything already here isn't ours
        offYaw = 0f; offPitch = 0f; moveLen = 0
        nextMoveIn = nextMoveDelay()
        running = true
        returning = false
        state = FishingState.FIND
        stand = null
        fishTarget = null
        trackerAtStart = FishingTracker.numbers()
        breakMs = 0L; hotspotsVisited = 0; rareLog.clear()
        statGround = 0; statRelink = 0; statSplash = 0; statDrift = 0; statLava = 0
        hotspot = null
        badHotspots.clear()
        aimTries = 0
        expectedSlot = - 1
        wait = 0
        groundFails = 0
        waiting.clear()
        ignored.clear()
        info("&aAuto Fishing started. Press the key again to stop.")
    }

    internal fun failStop(why: String) {
        journal.log("FAILSAFE  $why  (state=$state, status=$status)")
        stop("stopped: $why")
        NotificationManager.push("Auto Fishing stopped", why)
        dev.marceline.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 0.6f)
    }

    /** View priorities (AimBroker): fighting may take the view from anything travelling; moving and casting is travel. */
    internal val AIM_TRAVEL = dev.marceline.utils.AimPriority.TRAVEL
    internal val AIM_COMBAT = dev.marceline.utils.AimPriority.COMBAT

    /** The priority for what Auto Fishing is doing now. */
    internal fun aimPriority() = if (state == FishingState.FIGHT) AIM_COMBAT else AIM_TRAVEL

    internal fun stop(reason: String?) {
        dev.marceline.utils.Aim.release(this)
        if (running) runCatching { writeReport(reason ?: "stopped") }
        if (running) journal.log("stop  ${reason ?: "-"}  (state=$state, kills=$kills, creatures=$creaturesCaught)")
        if (running) releaseCentre()
        centreTarget = null
        track = null
        running = false
        hop = null
        AutoRoutes.releaseAll()
        mc.options.keyUse.isDown = false
        if (reason != null) info("&eAuto Fishing $reason.")
    }
}
//#endif
