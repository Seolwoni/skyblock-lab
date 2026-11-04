package com.github.noamm9.features.impl.fishing

//#if CHEAT
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.TextInputSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.routes.AssistBridge
import com.github.noamm9.features.impl.routes.AutoRoutes
import com.github.noamm9.ui.notification.NotificationManager
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.Render2D.drawString
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
 * This file holds the settings, state and main loop. The rest lives next to it:
 * AutoFishingCast (throw solver, reading the hook), AutoFishingCombat (sea creatures, fights, tracking),
 * AutoFishingTravel (Hotspots, walking, hops), AutoFishingSafety (block centre, lava) and AutoFishingSell.
 */
object AutoFishing: Feature("Fishes by itself in water or lava: casts, reels in on the bite, recasts and kills sea creatures as soon as they come up. Human-like delays and smooth head movement (Reel Assist style).") {
    internal val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Look at the water or lava and press it to start, press again to stop")
    internal val fluid by DropdownSetting("Fluid", 0, listOf("Auto", "Water", "Lava")).withDescription("Auto: works out water or lava from where the hook lands. Only used to tell a hook that fell on the ground")

    internal val reelMin by SliderSetting("Reel Delay Min", 2, 1, 17, 1, " ticks").section("Timing").withDescription("Shortest wait after the bite before reeling in")
    internal val reelMax by SliderSetting("Reel Delay Max", 9, 1, 17, 1, " ticks").withDescription("Longest wait after the bite (17 at most, so the fish doesn't get away)")
    internal val castMin by SliderSetting("Cast Delay Min", 6, 1, 60, 1, " ticks").withDescription("Shortest wait before casting again")
    internal val castMax by SliderSetting("Cast Delay Max", 34, 1, 80, 1, " ticks")
    internal val hesitate by SliderSetting("Hesitation", 12, 0, 40, 1, "%").withDescription("Chance that a cast comes a little later than usual (0.3-1 s more)")
    internal val aimWobble by SliderSetting("Cast Aim Wobble", 0.5, 0.0, 2.0, 0.1, "°").withDescription("Each cast is aimed a tiny bit differently, like a hand that never lands on the exact same spot")
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
    internal val castFail by SliderSetting("Cast Fail", 1.5, 0.0, 10.0, 0.5, "%").section("Mistakes").withDescription("How often a cast comes out clearly off (the hand slips); a cast that misses gets recast")
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
    internal val rareList by com.github.noamm9.config.types.TextInputSetting("Rare Creatures", "Thunder|Lord Jawbus|Water Hydra|Sea Emperor|Loch Emperor|Great White Shark|Phantom Fisher|Grim Reaper|Yeti|Reindrake|Titanoboa|Plhlegblast|Fiery Scuttler|Ragnarok|Abyssal Miner|Wiki Tiki|Carrot King|Nessie").withDescription("Names, separated by |")
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
    internal val resumeAfterReboot by ToggleSetting("Come Back After Reboot", true).withDescription("Only after the server's own restart warning: if you're moved within 3 minutes, warp back to the island and carry on fishing. Any other move still stops")
    internal val warpCommand by com.github.noamm9.config.types.TextInputSetting("Warp Command", "").withDescription("The command back to your fishing island, without the slash (empty = the usual one, e.g. warp crimson)")
    internal val hotspotRange by SliderSetting("Search Range", 200, 16, 400, 8, " blocks").withDescription("How far it looks for a Hotspot. Hotspots seen earlier are remembered for 3 minutes, even after they're out of sight")
    internal val useEther by ToggleSetting("Etherwarp On The Way", true).withDescription("Plans etherwarps (and AOTV hops) to get to far or high spots, checking where each one lands, like Secret Routes does")
    internal val showRoute by ToggleSetting("Show Route", true).withDescription("Draws the planned walk and the next etherwarp / AOTV landing in the world")
    internal val hotspotRadius by SliderSetting("Hotspot Size", 2.5, 1.0, 6.0, 0.5, " blocks").withDescription("How close to the Hotspot's centre the hook has to land; it adjusts the aim until it does")
    internal val stayHome by ToggleSetting("Return To Spot", true).withDescription("Walks back to the fishing spot if you get pushed away from it")

    internal val autoSell by ToggleSetting("Auto Sell", false).section("Auto Sell").withDescription(
        "When the inventory is nearly full: opens the SkyBlock Menu (hotbar slot below), clicks the Booster Cookie, sells only the items on your sell list to the NPC, closes it and goes back to fishing"
    )
    internal val sellWhenFree by SliderSetting("Sell When Free Slots", 3, 0, 15, 1).withDescription("Sells once this few empty slots are left")
    internal val menuSlot by SliderSetting("Menu Slot", 9, 1, 9, 1).withDescription("Hotbar slot of the SkyBlock Menu (the Nether Star)")
    internal val sellListKey by KeybindSetting("Add To Sell List Key").withDescription("In any inventory, point at an item and press this to add it to (or remove it from) the sell list. Only items on the list are ever sold")
    val sellList by com.github.noamm9.config.types.TextInputSetting("Sell List", "").withDescription("The items Auto Sell may sell, by name, separated by | (fill it with the key above, or type names here)")
    internal val clearSellList by com.github.noamm9.config.types.ButtonSetting("Clear Sell List") {
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
    internal val discordUrl by com.github.noamm9.config.types.TextInputSetting("Webhook URL", "").withDescription(
        "Channel settings > Integrations > Webhooks > New Webhook > Copy Webhook URL, then paste it here"
    )
    internal val discordTest by com.github.noamm9.config.types.ButtonSetting("Send Test Message") {
        com.github.noamm9.utils.DiscordWebhook.send(discordUrl.value, "🎣 Riftwalk 연결 확인", "웹후크가 잘 연결됐어요. 낚시가 끝나면 여기로 알려드릴게요.", 0x8B5CF6) { ok, why ->
            info(if (ok) "&aDiscord test sent." else "&cDiscord test failed: $why")
        }
    }
    internal val sendLogsButton by com.github.noamm9.config.types.ButtonSetting("Send Logs To Discord") { sendLogs() }
        .withDescription("Uploads fishing.log and the latest session reports to your Discord webhook - handy on a phone, where the files are hard to reach (needs the Webhook URL above)")
    internal val checkButton by com.github.noamm9.config.types.ButtonSetting("Fishing Check") { runCatching { fishingCheck() }.onFailure { info("&cCheck failed: ${it.message}") } }
        .section("Server Check").withDescription("Prints what the mod can see right now: hotbar item IDs, floating texts nearby (hotspot / bite / name tags), the hook, tab stats, mana and bait. Stand by the water with the rod out")
    internal val copyLogButton by com.github.noamm9.config.types.ButtonSetting("Copy Log Path") {
        mc.keyboardHandler.clipboard = journal.path
        info("&7Fishing log: &f${journal.path} &8(copied)")
    }.withDescription("Copies where fishing.log is, to send it over after a test")
    internal val stopAfterMin by SliderSetting("Stop After", 0, 0, 600, 10, " min").withDescription("Ends the session on its own after this long (0 = never)")
    internal val stopAfterKills by SliderSetting("Stop After Kills", 0, 0, 5000, 50).withDescription("Ends the session after this many sea creature kills (0 = never)")
    internal val stopHealth by SliderSetting("Stop Below Health", 30, 0, 90, 5, "%").section("Safety").withDescription("Stops when your health drops below this (0 = never)")
    internal val chatInfo by ToggleSetting("Chat Messages", true)
    internal val statusHud by ToggleSetting("Status HUD", true)

    internal enum class State { FIND, TRAVEL, CAST, WAIT_BITE, REEL, FIGHT, SELL, BREAK, RESUME }

    internal val RANDOM = Random()
    internal var timing = com.github.noamm9.utils.HumanTiming()
    internal var hopThisStretch = true
    internal var stretchLeft = 0
    internal var justCaughtSomething = false
    internal val WITHER_BLADES = com.github.noamm9.utils.SkyblockText.WITHER_BLADES
    internal val WHIPS = mapOf(1 to com.github.noamm9.utils.SkyblockText.SOUL_WHIP, 2 to com.github.noamm9.utils.SkyblockText.FLAMING_FLAY)
    internal val NPC_CLASSES = setOf("Villager", "WanderingTrader")

    internal var running = false
    internal var state = State.CAST
    internal var wait = 0
    internal var timer = 0
    internal var inFluidTicks = 0
    internal var hitCooldown = 0
    internal var status = "idle"
    internal var lastMobIds = emptySet<Int>()
    internal val waiting = HashMap<Int, Int>()
    internal val inReach = HashMap<Int, Pair<Int, Float>>() // id -> ticks in reach without losing health, last health
    internal var manaWait = 0
    internal var fightTicks = 0
    internal var blastBlocked = false
    internal var centreTicks = 0
    internal var centreTarget: Vec3? = null
    internal val ignored = HashSet<Int>()
    internal var groundTicks = 0
    internal var groundFails = 0
    internal var lastHookPos: Vec3? = null

    // look: where we fish (anchor) + a small eased offset
    internal var anchorYaw = 0f
    internal var anchorPitch = 0f
    internal var offYaw = 0f
    internal var offPitch = 0f
    internal var fromYaw = 0f
    internal var fromPitch = 0f
    internal var toYaw = 0f
    internal var toPitch = 0f
    internal var moveTick = 0
    internal var moveLen = 0
    internal var nextMoveIn = 0
    internal var returning = false

    // hotspots / location
    internal var hotspot: Entity? = null  // the floating "HOTSPOT" text (armor stand or text display)
    internal var stand: Vec3? = null
    internal val badHotspots = HashSet<Int>()
    internal var aimTries = 0
    internal var travelStuck = 0
    internal var ownMoveTicks = 0
    internal var expectedSlot = - 1
    internal var lastLookYaw: Float? = null
    internal var lastLookPitch = 0f
    internal var lastTickPos: Vec3? = null
    internal var lastTickLevel: Any? = null
    internal var lastTickPlayer: Any? = null

    // safe walking (own path, avoids lava / water)
    internal var path: List<BlockPos> = emptyList()
    internal var pathIndex = 0
    internal var pathGoal: Vec3? = null
    internal var lastSafe: Vec3? = null
    internal var lavaTicks = 0
    internal var hopWait = 0
    internal var repath = 0

    // selling
    internal var sellStep = 0
    internal var sellTimer = 0
    internal var sellGap = 0
    internal var sellTitle: String? = null
    internal val sellQueue = ArrayDeque<Int>()
    internal var sellFails = 0
    internal var sold = 0

    // planned hops (etherwarp / AOTV) while travelling
    internal class Hop(val ether: Boolean, val slot: Int, val target: BlockPos?, val yaw: Float, val pitch: Float, val landing: Vec3)
    internal var hop: Hop? = null
    internal var hopPhase = 0
    internal var hopTimer = 0
    internal var hopFrom: Vec3 = Vec3.ZERO
    internal var planIn = 0
    internal var radarStage = 0
    internal var radarTicks = 0
    internal var radarCooldown = 0
    internal var radarOrigin: Vec3? = null
    internal val radarPts = ArrayList<Vec3>()
    internal val radarLines = ArrayList<Pair<Vec3, Vec3>>() // where we read it, flat direction
    internal var radarGuess: Vec3? = null
    internal val remembered = HashMap<Long, Pair<Vec3, Long>>() // block key -> position, last seen
    internal var scouting = false

    // aiming the cast
    internal var aimTarget: Vec3? = null
    /** This cast's small hand wobble on top of the aim. */
    internal var wobYaw = 0f
    internal var wobPitch = 0f

    // session numbers for the status card
    internal var sessionStart = 0L
    internal var kills = 0
    internal var creaturesCaught = 0
    internal val mineAlive = HashSet<Int>()
    internal var lastLanding: Vec3? = null
    internal var nextBreakAt = 0L
    internal var breakUntil = 0L
    internal var glanceIn = 0

    // which creatures are ours: they come up at our hook right after we reel in
    internal class Reel(val tick: Int, val at: Vec3)
    internal val reels = ArrayDeque<Reel>()
    internal val firstSeen = HashSet<Int>()
    internal val mine = HashSet<Int>()
    internal val seenAt = HashMap<Int, Int>()
    internal val seenPos = HashMap<Int, Vec3>()
    internal val catches = ArrayDeque<Pair<Int, String>>() // tick, lower-case catch message
    internal val chase = HashMap<Int, Pair<Int, Double>>() // id -> ticks coming at us, last distance
    internal var claimsLeft = 0
    internal var catchMsgsWork = false
    internal val journal = com.github.noamm9.utils.Journal("fishing")
    internal var lastLoggedState: State? = null
    internal var biteAt: Vec3? = null               // where the hook was at the bite (it can vanish before we reel)
    internal var tabStats = ""
    internal var guiWasOpen = false
    internal var guiLockUntil = 0           // no reacting to a bite right after closing a menu
    internal var settledAt: Vec3? = null    // where the hook came to rest (drift check)
    internal var lastParticleRecast = - 1000
    internal val dustAt = ArrayDeque<Int>()   // ticks of dust particles seen right at the hook                      // "SCC 21% · DHC 8%" from the tab list
    internal val claimedAt = HashMap<Int, Long>()   // id -> when it became ours (despawn warning)
    internal val despawnWarned = HashSet<Int>()
    internal val rareIds = HashSet<Int>()
    internal var hotspotAt: Vec3? = null            // where the hotspot was when we picked it (it can move)
    internal val castHp = HashMap<Int, Float>()
    internal var immuneWait = 0
    internal val immuneWaited = HashSet<Int>()
    internal val deaths = ArrayDeque<Pair<Int, Vec3>>()   // tick, where one of ours died (split check)
    internal var arrivedAt = - 1000
    internal var nearGoalTicks = 0
    internal var goldenSeenAt = - 100000
    internal var fishTarget: Vec3? = null
    internal var walkBest = Double.MAX_VALUE
    internal var walkNoProgress = 0
    internal var walkReplans = 0
    internal var fightLeftAt = - 1000
    internal var noReachTicks = 0
    internal var aimFixes = 0           // the water / lava point we walked to (no hotspot)
    internal var rebootAt = 0L
    internal var rebootSpot: Vec3? = null
    internal var rebootHotspot = false
    internal var rebootArea: String? = null
    internal var resumeStage = 0
    internal var resumeWait = 0
    internal var warpTries = 0
    // session report
    internal var trackerAtStart: Map<String, Long> = emptyMap()
    internal var breakMs = 0L
    internal var hotspotsVisited = 0
    internal val rareLog = ArrayList<String>()
    internal var statGround = 0
    internal var statRelink = 0
    internal var statSplash = 0
    internal var statDrift = 0
    internal var statLava = 0
    internal var lastReport: List<String> = emptyList()     // creature health at our last blast (immunity check)

    // noticing sea creatures
    internal val noticeAt = HashMap<Int, Int>()
    internal var tickCount = 0
    internal var fightWait = 0
    internal var castDownPitch = 0f
    internal var hypeStage = 0
    internal var hypeBeat = 0

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) mc.player?.let { com.github.noamm9.utils.Humanize.settle(it) }
            if (running) runCatching(::tick).onFailure { com.github.noamm9.utils.Safely.report("AutoFishing tick", it); stop("error: ${it.message}") }
        }
        register<com.github.noamm9.event.impl.ChatMessageEvent> {
            if (! running || ! failsafe.value) return@register
            val t = event.unformattedText
            if (stopOnParty.value) com.github.noamm9.utils.WatchSigns.partyInvite(t)?.let { return@register failStop("party invite from $it") }
            if (stopOnMessage.value) com.github.noamm9.utils.WatchSigns.privateMessage(t)?.let { return@register failStop("private message from $it") }
        }
        register<com.github.noamm9.event.impl.ChatMessageEvent> { if (readShared.value) com.github.noamm9.utils.Safely("AutoFishing shared hotspot") { readSharedHotspot(event.unformattedText) } }
        register<com.github.noamm9.event.impl.ChatMessageEvent> {
            val t = event.unformattedText.replace(Regex("§."), "")
            if (running && t.contains(com.github.noamm9.utils.SkyblockText.GOLDEN_FISH, ignoreCase = true) && ! t.contains("TROPHY FISH", true) && ! Regex("^(?:\\[[^]]+] )*[A-Za-z0-9_]{2,16}: ").containsMatchIn(t)) onGoldenFish("chat: ${t.take(60)}")
        }
        register<com.github.noamm9.event.impl.ChatMessageEvent> {
            if (running && event.unformattedText.contains(com.github.noamm9.utils.SkyblockText.REBOOT, ignoreCase = true)) {
                armReboot()
                NotificationManager.push("Server restarting", "&fThe server will restart soon - Auto Fishing keeps going until you're moved")
                com.github.noamm9.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 0.8f)
                journal.log("server reboot warning")
            }
        }
        register<com.github.noamm9.event.impl.ChatMessageEvent> {
            if (! running) return@register
            val t = event.unformattedText.replace(Regex("§."), "").trim()
            val lastReel = reels.lastOrNull()?.tick ?: return@register
            if (tickCount - lastReel > 30 || t.length > 160) return@register
            if (Regex("^(?:\\[[^]]+] )*[A-Za-z0-9_]{2,16}: ").containsMatchIn(t)) return@register // a player talking
            if (t.contains(com.github.noamm9.utils.SkyblockText.DOUBLE_HOOK, ignoreCase = true)) { claimsLeft++; return@register }
            // a known catch line names the creature even when the text doesn't ("What is this creature!?" = Yeti)
            val known = com.github.noamm9.utils.SkyblockText.creatureFromCatch(t)
            catches += tickCount to (t.lowercase() + (known?.let { " | " + it.lowercase() } ?: ""))
            if (known != null) journal.log("catch line: $known")
            claimsLeft++
        }
        register<WorldChangeEvent> {
            remembered.clear(); radarLines.clear(); radarStage = 0
            if (running) { if (rebootWindow()) beginResume() else stop("world changed") }
        }
        register<com.github.noamm9.event.impl.KeyboardEvent.KeyPressed> {
            if (event.action != com.mojang.blaze3d.platform.InputConstants.PRESS) return@register
            val screen = mc.gui.screen() as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*> ?: return@register
            if (! sellListKey.matches(event.keyEvent.key, mouse = false)) return@register
            val slot = (screen as com.github.noamm9.mixin.IAbstractContainerScreen).hoveredSlot ?: return@register
            val name = itemKey(slot.item) ?: return@register
            val list = sellNames().toMutableSet()
            val added = list.add(name)
            if (! added) list.remove(name)
            sellList.value = list.joinToString("|")
            com.github.noamm9.config.ConfigManager.save()
            NotificationManager.push("Sell List", if (added) "&fAdded &d$name" else "&fRemoved &d$name")
            event.isCanceled = true
        }
        register<com.github.noamm9.event.impl.CheckEntityGlowEvent> {
            if (! running || ! showMarkers.value || ! creatureGlow.value || event.entity.id !in mineAlive) return@register
            event.color = if (event.entity.id in rareIds) java.awt.Color(255, 196, 64) else com.github.noamm9.ui.clickgui.components.settings.Style.accent2
        }
        register<com.github.noamm9.event.impl.RenderWorldEvent> { com.github.noamm9.utils.Safely("AutoFishing frame") { com.github.noamm9.utils.Humanize.frame(); trackFrame() } }
        register<com.github.noamm9.event.impl.RenderWorldEvent> {
            if (! running || ! showMarkers.value) return@register
            val level = mc.level ?: return@register
            with(com.github.noamm9.utils.render.world.Render3D) {
                lastLanding?.takeIf { state != State.TRAVEL }?.let { l ->
                    val c = com.github.noamm9.ui.clickgui.components.settings.Style.accentColor
                    event.ctx.renderBoxBounds(l.x - 0.3, l.y - 0.1, l.z - 0.3, l.x + 0.3, l.y + 0.5, l.z + 0.3, c, c.withAlpha(70), phase = true, lineWidth = 2)
                }
                val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
                for (id in mineAlive) {
                    val e = level.getEntity(id) ?: continue
                    if (! e.isAlive) continue
                    val c = if (id in rareIds) java.awt.Color(255, 196, 64) else com.github.noamm9.ui.clickgui.components.settings.Style.accent2
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
                    val hp = com.github.noamm9.utils.SkyblockText.MOB_HEALTH.find(raw)
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
        register<com.github.noamm9.event.impl.RenderWorldEvent> {
            if (! running || ! showRoute.value) return@register
            val p = mc.player ?: return@register
            with(com.github.noamm9.utils.render.world.Render3D) {
                // where we're fishing / heading: a tall beam you can see from far away, with the distance
                val spot = hotspot?.takeIf { it.isAlive }?.position() ?: stand
                if (spot != null) {
                    val beam = if (hotspot != null) java.awt.Color(236, 72, 153) else java.awt.Color(80, 220, 255)
                    val d = p.position().distanceTo(spot)
                    event.ctx.renderBoxBounds(spot.x - 0.25, spot.y, spot.z - 0.25, spot.x + 0.25, spot.y + 30, spot.z + 0.25, beam.withAlpha(160), beam.withAlpha(55), phase = true, lineWidth = 1)
                    hotspot?.let { event.ctx.renderCircle(Vec3(spot.x, spot.y - 1.5, spot.z), hotspotRadius.value, beam, 2, phase = true) }
                    val label = (if (hotspot != null) "Hotspot" else "Fishing spot") + " §7${d.toInt()}m"
                    event.ctx.renderString(label, spot.x, spot.y + 2.5, spot.z, beam, (1.2 + d / 40).coerceAtMost(4.0), phase = true)
                }
                if (state != State.TRAVEL) return@register
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
                val col = if (h.ether) java.awt.Color(236, 72, 153) else java.awt.Color(120, 200, 255)
                with(com.github.noamm9.utils.render.world.Render3D) {
                    event.ctx.renderLine(p.eyePosition, h.landing.add(0.0, 0.2, 0.0), col, 2, phase = true)
                    event.ctx.renderBlock(BlockPos.containing(h.landing.x, h.landing.y - 0.5, h.landing.z), col, fill = false, phase = true, lineWidth = 3)
                }
            }
            stand?.let { with(com.github.noamm9.utils.render.world.Render3D) { event.ctx.renderBlock(BlockPos.containing(it.x, it.y - 0.5, it.z), java.awt.Color(80, 255, 140), fill = false, phase = true, lineWidth = 2) } }
        }
        register<MainThreadPacketReceivedEvent.Pre> {
            val pk = event.packet
            if (running && radarStage == 1 && pk is net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket) radarParticle(pk.x, pk.y, pk.z)
            if (running && state == State.WAIT_BITE && pk is net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket && pk.particle.type == net.minecraft.core.particles.ParticleTypes.DUST) {
                val h = mc.player?.fishing
                if (h != null && h.position().distanceToSqr(pk.x, pk.y, pk.z) < 1.6 * 1.6) dustAt += tickCount
            }
        }
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! running || ! failsafe.value || ownMoveTicks > 0 || state == State.RESUME) return@register
            val name = event.packet.javaClass.simpleName
            if (event.packet is ClientboundPlayerPositionPacket || name.contains("PlayerRotation")) mc.execute { if (running) failStop("the server moved or turned you") }
        }

        hudElement("Auto Fishing Status", { statusHud.value }, { running }) { ctx, example ->
            drawCard(ctx, example)
        }.also { it.themedPanel = false }
    }

    internal val fishIcon = net.minecraft.resources.Identifier.fromNamespaceAndPath(com.github.noamm9.NoammAddons.MOD_ID, "textures/gui/icons/fishing.png")

    internal fun stateLabel() = when (state) {
        State.FIND -> "Looking for a hotspot"
        State.TRAVEL -> "Travelling"
        State.CAST -> if (returning) "Re-aiming" else "Casting"
        State.WAIT_BITE -> "Waiting"
        State.REEL -> "Reeling"
        State.FIGHT -> "Attacking"
        State.SELL -> "Selling"
        State.BREAK -> "On a break"
        State.RESUME -> "Coming back"
    }

    internal fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "${s / 3600}h ${(s / 60) % 60}m" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
    }

    /** The status card: icon, state, kills per hour big, then sea creatures / session / per hour / next break. */
    internal fun drawCard(ctx: net.minecraft.client.gui.GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        val st = com.github.noamm9.ui.clickgui.components.settings.Style
        val w = 150f
        val stats = if (example) "SCC 21%  DHC 8%  Speed 120" else tabStats
        val h = if (stats.isNotEmpty()) 104f else 92f
        val now = System.currentTimeMillis()
        val session = if (example) 754_000L else now - sessionStart
        val hours = session / 3_600_000.0
        val killsHr = if (example) 1_412 else if (hours > 0.003) (kills / hours).toInt() else 0
        val label = if (example) "Waiting" else stateLabel()
        val breakText = when {
            example -> "21m 4s"
            ! longBreaks.value -> "off"
            state == State.BREAK -> "now " + clock(breakUntil - now)
            nextBreakAt > 0 -> clock(nextBreakAt - now)
            else -> "-"
        }
        st.drawShadow(ctx, 0f, 0f, w, h, 6f, 0.6f)
        st.roundRect(ctx, 0f, 0f, w, h, 6f, java.awt.Color(15, 11, 23, 225))
        st.roundRect(ctx, 10f, 1f, w - 20f, 0.75f, 0.4f, java.awt.Color(255, 255, 255, 22))
        // icon in an accent ring
        st.roundRect(ctx, 7f, 7f, 18f, 18f, 9f, st.accentColor)
        st.roundRect(ctx, 8.5f, 8.5f, 15f, 15f, 7.5f, java.awt.Color(15, 11, 23))
        ctx.pose().pushMatrix()
        ctx.pose().translate(10.5f, 10.5f)
        ctx.pose().scale(11f / 64f, 11f / 64f)
        with(com.github.noamm9.utils.render.Render2D) { ctx.drawTexture(fishIcon, 0, 0, 64, 64, st.accentColor) }
        ctx.pose().popMatrix()
        ctx.drawString("§lAuto Fishing", 30f, 8f, st.mix(st.accentColor, java.awt.Color.WHITE, 0.35f), shadow = false)
        ctx.drawString(label, 30f, 17.5f, java.awt.Color(150, 140, 175), shadow = false, scale = 0.9f)

        ctx.drawString("KILLS / HR", 8f, 32f, java.awt.Color(110, 100, 135), shadow = false, scale = 0.75f)
        // the headline number, a size up
        ctx.pose().pushMatrix()
        ctx.pose().translate(8f, 39.5f)
        ctx.pose().scale(1.55f, 1.55f)
        st.drawTabular(ctx, "%,d".format(killsHr), 0f, 0f, java.awt.Color(230, 224, 245))
        ctx.pose().popMatrix()
        fun cell(title: String, value: String, x: Float, y: Float, right: Boolean) {
            val tw = with(com.github.noamm9.utils.render.RenderHelper) { title.width() } * 0.7f
            val vw = with(com.github.noamm9.utils.render.RenderHelper) { value.width() } * 0.9f
            ctx.drawString(title, if (right) x - tw else x, y, java.awt.Color(110, 100, 135), shadow = false, scale = 0.7f)
            ctx.drawString(value, if (right) x - vw else x, y + 7.5f, java.awt.Color(214, 206, 232), shadow = false, scale = 0.9f)
        }
        cell("SEA CREATURES", if (example) "38" else "$creaturesCaught", 8f, 55f, false)
        cell("SESSION TIME", clock(session), w - 8f, 55f, true)
        cell("KILLS", if (example) "36" else "$kills", 8f, 73f, false)
        cell("NEXT BREAK", breakText, w - 8f, 73f, true)
        if (stats.isNotEmpty()) {
            st.roundRect(ctx, 8f, 91f, w - 16f, 0.5f, 0.25f, java.awt.Color(255, 255, 255, 18))
            ctx.drawString(stats, 8f, 94f, java.awt.Color(150, 140, 175), shadow = false, scale = 0.7f)
        }
        return w to h
    }

    override fun onDisable() {
        super.onDisable()
        if (running) stop(null)
    }

    /** Sea Creature Chance / Double Hook Chance / Fishing Speed from the tab list's stat lines, if it shows them. */
    internal fun readTabStats(): String = runCatching {
        val lines = com.github.noamm9.utils.TabListUtils.getTabList().map { it.first.string.replace(Regex("§."), "").trim() }
        fun stat(key: String) = lines.firstNotNullOfOrNull { Regex("$key:?\\s*([\\d.,]+%?)").find(it)?.groupValues?.get(1) }
        listOfNotNull(stat("Sea Creature Chance")?.let { "SCC $it" }, stat("Double Hook Chance")?.let { "DHC $it" }, stat("Fishing Speed")?.let { "Speed $it" })
            .joinToString("  ")
    }.getOrDefault("")

    /** A Golden Fish is out: tell the player loudly (once a minute at most), and pause if they want to catch it themselves. */
    internal fun onGoldenFish(how: String) {
        if (goldenFish.value == 0 || tickCount - goldenSeenAt < 1200) return
        goldenSeenAt = tickCount
        journal.log("GOLDEN FISH  $how")
        NotificationManager.push("Golden Fish!", "&6A Golden Fish is out &7($how)", 8000L)
        com.github.noamm9.utils.ChatUtils.showTitle("§6§lGOLDEN FISH", "§fit's out - go get it")
        repeat(3) { com.github.noamm9.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1f, 1.5f) }
        if (goldenFish.value == 2) {
            mc.player?.let { pl -> if (pl.fishing != null) rod()?.let { hold(it); use(pl) } }
            stop("paused for the Golden Fish - start again when you've caught it")
        }
    }

    /** How far our weapon reaches: the Hyperion blast, the whip, or a sword swing. */
    internal fun fightReach(): Double {
        val whip = WHIPS[weaponType.value]?.let { id -> PlayerUtils.findHotbarSlot { it.skyblockId == id } }
        return when {
            whip != null -> whipRange.value
            hasHype() || PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES } != null -> blastRange.value
            else -> 3.2
        }
    }

    /** "1.2k" -> 1200.0, "3,400" -> 3400.0 */
    internal fun parseShort(v: String): Double {
        val t = v.replace(",", "")
        val mult = when (t.lastOrNull()?.lowercaseChar()) { 'k' -> 1e3; 'm' -> 1e6; 'b' -> 1e9; else -> 1.0 }
        return (t.trimEnd('k', 'K', 'm', 'M', 'b', 'B').toDoubleOrNull() ?: 0.0) * mult
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
        timing = com.github.noamm9.utils.HumanTiming().apply(com.github.noamm9.utils.Humanize.profile())
        lastLookYaw = null
        lastTickPos = null; lastTickLevel = null; lastTickPlayer = null
        wobYaw = 0f; wobPitch = 0f
        aimTarget = null
        noticeAt.clear()
        path = emptyList()
        sellFails = 0
        sold = 0
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
        state = State.FIND
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
        com.github.noamm9.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 0.6f)
    }

    /** "[TAG] name" of a player near us whose name carries a staff tag, or null. */
    internal fun staffNearby(p: LocalPlayer): String? {
        val tags = com.github.noamm9.utils.WatchSigns.parseTags(staffTags.value)
        val r = staffRange.value.toDouble()
        for (o in p.level().players()) {
            if (o === p || o.distanceToSqr(p) > r * r) continue
            val shown = o.displayName?.string ?: o.name.string
            val tag = com.github.noamm9.utils.WatchSigns.staffTag(shown, tags)
                ?: mc.connection?.getPlayerInfo(o.uuid)?.tabListDisplayName?.string?.let { com.github.noamm9.utils.WatchSigns.staffTag(it, tags) }
            if (tag != null) return "[$tag] ${o.name.string}"
        }
        // name tags floating over players (servers often draw ranks as a separate text)
        for ((_, text) in com.github.noamm9.utils.FloatingText.near(p.level(), p.boundingBox.inflate(r))) {
            val tag = com.github.noamm9.utils.WatchSigns.staffTag(text, tags) ?: continue
            if (Regex("\\[$tag\\+*] ?[A-Za-z0-9_]{1,16}", RegexOption.IGNORE_CASE).containsMatchIn(text.replace(Regex("§."), ""))) return text.replace(Regex("§."), "").take(40)
        }
        return null
    }

    internal fun stop(reason: String?) {
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

    // ------------------------------------------------------------------ timing

    internal fun rand(min: Int, max: Int) = if (max <= min) min else min + RANDOM.nextInt(max - min + 1)

    /** Reel Assist's delay: mostly the lower third of [min, max], 8% of the time a slow reaction. */
    internal fun delay(min: Int, max: Int): Int {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        if (hi <= lo) return lo
        if (timingModel.value == 0) return rand(lo, hi)
        if (timingModel.value == 2) {
            timing.slipChance = slipChance.value / 100.0
            timing.drift = drift.value / 100.0
            timing.fatiguePerHour = fatigue.value / 100.0
            return timing.next(lo, hi)
        }
        val span = (hi - lo).toDouble()
        val v = if (RANDOM.nextDouble() < 0.08) lo + span * (0.6 + 0.4 * RANDOM.nextDouble())
        else lo + span * 0.3 + RANDOM.nextGaussian() * span * 0.15
        return Math.round(v).toInt().coerceIn(lo, hi)
    }

    /** Wait before the next cast: the timing model, plus reading the catch and the odd short break. */
    internal fun castWait(): Int {
        var t = delay(castMin.value, castMax.value)
        if (lookAtCatch.value && justCaughtSomething) t += timing.uniform(8, 25)
        justCaughtSomething = false
        t += timing.maybeBreak(breakChance.value, 40, 120)
        if (RANDOM.nextInt(100) < hesitate.value) t += timing.uniform(6, 20)
        if (com.github.noamm9.utils.HumanMotion.chance(zoneOut.value, RANDOM)) {
            val z = com.github.noamm9.utils.HumanMotion.between((zoneOutMin.value * 20).toInt(), (zoneOutMax.value * 20).toInt(), RANDOM)
            t += z
            journal.log("zone out ${z / 20.0}s")
        }
        return t
    }

    internal fun nextMoveDelay() = (jitterEvery.value * 20 * (0.8 + 0.4 * RANDOM.nextDouble())).toInt()

    // ------------------------------------------------------------------ turning

    internal fun wrap(d: Float): Float {
        var x = d % 360f
        if (x > 180f) x -= 360f
        if (x < - 180f) x += 360f
        return x
    }

    /** Eased turn towards yaw / pitch. True once within [tol] degrees. */
    internal fun turn(p: LocalPlayer, yaw: Float, pitch: Float, tol: Float = 1.5f): Boolean {
        val dy = wrap(yaw - p.yRot)
        val dp = pitch - p.xRot
        if (abs(dy) <= tol && abs(dp) <= tol) return true
        if (! smoothTurns.value) {
            com.github.noamm9.utils.Humanize.setView(p, yaw, pitch)
            return true
        }
        // no overshoot while fighting: a creature on the move needs the aim to stay on it
        return com.github.noamm9.utils.Humanize.turnTo(p, yaw, pitch, tol, overshoot = state != State.FIGHT)
    }

    /** The small eased head movements around the bobber while waiting. Also follows the player's own camera moves. */
    internal fun lookMovement(p: LocalPlayer) {
        if (! lookJitter.value) return
        val expYaw = anchorYaw + offYaw + wobYaw
        val expPitch = anchorPitch + offPitch + wobPitch
        if (abs(wrap(p.yRot - expYaw)) > 0.3f || abs(p.xRot - expPitch) > 0.3f) {
            // the player turned the camera: fish where they look now
            anchorYaw = p.yRot - offYaw - wobYaw
            anchorPitch = p.xRot - offPitch - wobPitch
        }
        if (moveLen > 0) {
            moveTick++
            val t = (moveTick / moveLen.toFloat()).coerceAtMost(1f)
            val ease = t * t * (3 - 2 * t)
            offYaw = fromYaw + (toYaw - fromYaw) * ease
            offPitch = fromPitch + (toPitch - fromPitch) * ease
            com.github.noamm9.utils.Humanize.glide(p, anchorYaw + offYaw + wobYaw, (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f))
            if (t >= 1f) moveLen = 0
            return
        }
        if (--nextMoveIn > 0) return
        // a bounded random walk around the spot: each move is a small step from the last, never past Drift Range
        val step = jitterDegrees.value.toFloat()
        val bound = driftRange.value.toFloat()
        fromYaw = offYaw; fromPitch = offPitch
        toYaw = com.github.noamm9.utils.HumanMotion.walk(offYaw, step, bound, RANDOM)
        toPitch = com.github.noamm9.utils.HumanMotion.walk(offPitch, step * 0.6f, bound * 0.6f, RANDOM)
        moveTick = 0
        moveLen = rand(6, 14)
        nextMoveIn = nextMoveDelay()
    }

    // ------------------------------------------------------------------ main loop

    internal fun rod(): Int? {
        if (rodSlot.value in 1 .. 9) return rodSlot.value - 1
        return PlayerUtils.findHotbarSlot { it.item == Items.FISHING_ROD }
    }

    internal fun hold(slot: Int) {
        val p = mc.player ?: return
        if (p.inventory.selectedSlot != slot) p.inventory.selectedSlot = slot
        expectedSlot = slot
    }

    internal fun use(p: LocalPlayer) {
        mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
        p.swing(InteractionHand.MAIN_HAND)
    }

    internal fun tick() {
        if (state == State.RESUME) return resumeTick()
        relinkBobber()
        val guiOpen = mc.gui.screen() != null
        if (guiWasOpen && ! guiOpen) guiLockUntil = tickCount + timing.uniform(15, 50) // a person needs a moment after closing a menu
        guiWasOpen = guiOpen
        if (state != lastLoggedState) { journal.log("state ${lastLoggedState ?: "-"} -> $state  ($status)"); lastLoggedState = state }
        if (tickCount % 40 == 0) tabStats = readTabStats()
        if (tickCount % 20 == 0 && goldenFish.value > 0) mc.player?.let { pl ->
            com.github.noamm9.utils.FloatingText.near(pl.level(), pl.boundingBox.inflate(30.0, 15.0, 30.0))
                .firstOrNull { (_, txt) -> txt.contains(com.github.noamm9.utils.SkyblockText.GOLDEN_FISH, ignoreCase = true) }
                ?.let { onGoldenFish("seen ${pl.distanceTo(it.first).toInt()}m away") }
        }
        val goalHit = when {
            stopAfterMin.value > 0 && System.currentTimeMillis() - sessionStart > stopAfterMin.value * 60_000L -> "${stopAfterMin.value} min of fishing"
            stopAfterKills.value > 0 && kills >= stopAfterKills.value -> "${stopAfterKills.value} kills"
            else -> null
        }
        if (goalHit != null && state != State.FIGHT) {
            mc.player?.let { pl -> if (pl.fishing != null) rod()?.let { hold(it); use(pl) } }
            NotificationManager.push("Auto Fishing", "&fGoal reached: &d$goalHit")
            return stop("done - goal reached ($goalHit)")
        }
        val p = mc.player ?: return stop("no player")
        if (stopHealth.value > 0 && p.health / p.maxHealth * 100f < stopHealth.value) return stop("health is low")
        if (hitCooldown > 0) hitCooldown--
        if (ownMoveTicks > 0) ownMoveTicks--
        // a sudden big jump in where we look (more than any turn of ours can do in a tick): stop
        if (failsafe.value && lastLookYaw != null && ownMoveTicks <= 0) {
            val dy = abs(wrap(p.yRot - lastLookYaw!!))
            val dp = abs(p.xRot - lastLookPitch)
            if (kotlin.math.sqrt(dy * dy + dp * dp) >= 70f) return failStop("your view suddenly turned ${kotlin.math.sqrt(dy * dy + dp * dp).toInt()} degrees")
        }
        lastLookYaw = p.yRot
        lastLookPitch = p.xRot
        // a long jump in one tick that isn't ours (no warp / AOTV / Wither Impact just now)
        val here = p.position()
        // dying / respawning or a new world (lobby, other server) isn't a teleport: start counting again
        val sameWorld = lastTickLevel === p.level() && lastTickPlayer === p && ! p.isDeadOrDying
        val before = if (sameWorld) lastTickPos else null
        lastTickPos = here
        lastTickLevel = p.level()
        lastTickPlayer = p
        if (failsafe.value && stopOnTeleport.value && before != null && ownMoveTicks <= 0 && state != State.RESUME &&
            com.github.noamm9.utils.WatchSigns.teleported(before.x, before.y, before.z, here.x, here.y, here.z, teleportDistance.value.toDouble())
        ) return failStop("teleported ${before.distanceTo(here).toInt()} blocks without a warp")
        if (failsafe.value && stopOnStaff.value && tickCount % 20 == 0) staffNearby(p)?.let { return failStop("staff nearby: $it") }
        timing.tick()
        if (failsafe.value) {
            if (expectedSlot >= 0 && p.inventory.selectedSlot != expectedSlot) return failStop("your held item was changed")
            val sc = mc.gui.screen()
            if (sc is AbstractContainerScreen<*> && sc !is InventoryScreen && state != State.SELL && ownMoveTicks <= 0) return failStop("a menu opened by itself (${sc.title.string})")
        }

        tickCount++
        if (! p.isInLava && ! p.isInWater && p.onGround()) { lastSafe = p.position(); lavaTicks = 0 }
        if (p.isInLava) return escapeLava(p)

        when (state) {
            State.FIND -> return findHotspot(p)
            State.TRAVEL -> return travel(p)
            State.SELL -> return sell(p)
            else -> {}
        }
        // pushed away from the spot: walk back (not while fighting, sea creatures come to us)
        val home = stand
        if (stayHome.value && home != null && state != State.FIGHT && tickCount - arrivedAt > 60 &&
            (horizontalDist(p.position(), home) > 3.0 || abs(p.y - home.y) > 2.5)) {
            journal.log("pushed ${"%.1f".format(horizontalDist(p.position(), home))} blocks from the spot, going back")
            if (p.fishing != null) rod()?.let { hold(it); use(p) }
            state = State.TRAVEL
            path = emptyList()
            status = "pushed away, going back"
            return
        }

        claimOwn(p)
        // sea creatures first, the moment one comes up
        val seen = if (killCreatures.value && mc.gui.screen() == null) creatures(p) else emptyList()
        if (mineAlive.isNotEmpty()) mineAlive.removeIf { id ->
            val e = p.level().getEntity(id)
            (e == null || ! e.isAlive).also { if (it && e != null) { kills++; deaths += tickCount to e.position() } }
        }
        val nowMs = System.currentTimeMillis()
        for (id in mineAlive) {
            val at = claimedAt[id] ?: continue
            if (nowMs - at > 330_000 && despawnWarned.add(id)) // ~6 min and they despawn
                NotificationManager.push("Sea creature", "&fOne of yours is about to despawn &7(5.5 min old)")
        }
        // a person needs a moment to notice something new: each creature gets its own notice time
        // right after our own reel we know something may come up: a person who's ready reacts faster
        val ready = quickKill.value && reels.isNotEmpty() && tickCount - reels.last().tick < 60
        for (m in seen) noticeAt.getOrPut(m.id) { tickCount + if (ready && m.id in mine) delay(3, 8) else delay(noticeMin.value, noticeMax.value) }
        if (noticeAt.size > 200) noticeAt.keys.retainAll(seen.map { it.id }.toSet())
        val noticed = seen.filter { (noticeAt[it.id] ?: 0) <= tickCount }
        // only stop fishing for what we can actually hit from here; one bobbing up and down below us
        // (in and out of reach) mustn't make us reel in, take the Hyperion out and put it away over and over
        val reach = fightReach()
        val inReach = noticed.filter { p.distanceTo(it) <= reach }
        val coming = noticed.filter { p.distanceTo(it) <= reach * 1.6 && it.y >= p.y - 2.0 }
        val hurt = p.hurtTime > 0
        val mobs = when {
            state == State.FIGHT -> if (inReach.isNotEmpty() || coming.isNotEmpty() || hurt) noticed.filter { p.distanceTo(it) <= reach * 1.6 } else emptyList()
            tickCount - fightLeftAt < 60 && ! hurt -> inReach // just left a fight: only something really in reach brings us back
            quickKill.value -> coming.ifEmpty { inReach }
            else -> inReach
        }
        // leave a fight only after 1.5 s with nothing to hit, so it doesn't flap
        if (state == State.FIGHT && mobs.isEmpty() && ++noReachTicks < 30) { status = "watching ${noticed.firstOrNull()?.name?.string ?: "around"}"; return }
        if (mobs.isNotEmpty()) noReachTicks = 0
        if (state == State.FIGHT || mobs.isNotEmpty()) {
            if (mobs.isEmpty()) {
                fightLeftAt = tickCount
                noReachTicks = 0
                track = null
                blastBlocked = false
                hypeStage = 0
                hypeBeat = 0
                castDownPitch = 0f
                state = State.CAST
                returning = true
                wait = castWait()
                status = "back to fishing"
                return
            }
            if (state != State.FIGHT) {
                if (p.fishing != null) rod()?.let { hold(it); use(p) } // pull the line in first
                fightWait = if (quickKill.value) delay(1, 4) else delay(3, 10) // then a beat before turning round
            }
            justCaughtSomething = true
            if (state != State.FIGHT) fightTicks = 0
            // never stand there forever: after 40 s of one fight, leave whatever is left and fish again
            if (++fightTicks > 800) {
                mobs.forEach { ignored += it.id }
                fightTicks = 0
                info("&7Left a fight that went on for 40 s.")
                return
            }
            state = State.FIGHT
            if (fightWait > 0) { fightWait--; status = "noticed a sea creature"; return }
            fight(p, mobs)
            return
        }

        if (state == State.BREAK) return onBreak(p)

        // back to the fishing spot (eased) after a fight
        if (returning) {
            status = "looking back at the ${fluidName()}"
            if (! turn(p, anchorYaw + offYaw + wobYaw, anchorPitch + offPitch + wobPitch, 0.8f)) return
            com.github.noamm9.utils.Humanize.setView(p, anchorYaw + offYaw + wobYaw, anchorPitch + offPitch + wobPitch) // land exactly
            returning = false
        }
        if (state == State.WAIT_BITE) lookMovement(p)

        if (wait > 0) {
            wait--
            return
        }
        val rod = rod() ?: return stop("no fishing rod in the hotbar")
        val hook = p.fishing

        when (state) {
            State.CAST -> {
                if (moveLen > 0) return // don't cast in the middle of a head movement
                if (! centered(p)) { status = "stepping to the middle of the block"; return }
                val free = freeSlots(p)
                if (autoSell.value && free <= sellWhenFree.value && sellFails < 3 && sellables(p).isNotEmpty()) {
                    state = State.SELL
                    sellStep = 0
                    sellTimer = 0
                    sellGap = delay(4, 12)
                    status = "inventory nearly full, selling"
                    return
                }
                if (free == 0) return stop("the inventory is full")
                if (longBreaks.value && nextBreakAt > 0 && System.currentTimeMillis() >= nextBreakAt) {
                    if (hook != null) { hold(rod); use(p) }
                    val len = timing.uniform(minOf(breakLenMin.value, breakLenMax.value), maxOf(breakLenMin.value, breakLenMax.value))
                    breakUntil = System.currentTimeMillis() + len * 1000L
                    breakMs += len * 1000L
                    glanceIn = timing.uniform(60, 200)
                    state = State.BREAK
                    info("&7Taking a break for ${len / 60}m ${len % 60}s.")
                    return
                }
                val mf = com.github.noamm9.utils.ManaGuard.fraction
                if (killCreatures.value && pauseMana.value > 0 && mf != null && mf * 100 < pauseMana.value && hasHype() && manaWait < 600) {
                    manaWait++ // at most 30 s, then fish anyway
                    if (p.inventory.selectedSlot != rod) hold(rod) // wait with the rod, not standing there with the Hyperion
                    status = "waiting for mana (${(mf * 100).toInt()}%)"
                    return
                }
                manaWait = 0
                if (creatureCap.value > 0 && mineAlive.size >= creatureCap.value) {
                    status = "at the sea creature cap (${mineAlive.size}/${creatureCap.value})"
                    return
                }
                // settle on this cast's aim (with its wobble) first, then land on it exactly
                val ty = anchorYaw + offYaw + wobYaw
                val tp = (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f)
                if (! turn(p, ty, tp, 0.35f)) return
                com.github.noamm9.utils.Humanize.setView(p, ty, tp)
                // would this throw come down in the water / lava? if it hits the floor or a wall, aim again first
                if (hook == null && ! throwLandsInFluid(p, ty, tp)) {
                    if (++aimFixes > 3) {
                        aimFixes = 0
                        journal.log("the aim keeps hitting the ground - looking for water again")
                        fishTarget = null
                        return goToFluid(p)
                    }
                    val target = hotspot?.position() ?: fishTarget ?: lookingAtFluid(p) ?: nearestFluid(p, 12)
                    if (target == null) { journal.log("cast would land on the ground, no water near - finding some"); return goToFluid(p) }
                    journal.log("cast would land on the ground - re-aiming at ${BlockPos.containing(target).toShortString()}")
                    fishTarget = fishTarget ?: target
                    aimAtPoint(p, target)
                    status = "re-aiming at the ${fluidName()}"
                    return
                }
                aimFixes = 0
                hold(rod)
                if (hook != null) { // a line is still out: pull it in first
                    use(p)
                    wait = castWait()
                    return
                }
                use(p)
                nextCastAim(p)
                state = State.WAIT_BITE
                timer = 0
                inFluidTicks = 0
                groundTicks = 0
                lastHookPos = null
                status = "cast"
            }

            State.WAIT_BITE -> {
                if (hook == null) {
                    if (++timer > 20) { // hook never came / line broke
                        state = State.CAST
                        wait = castWait()
                    }
                    return
                }
                timer++
                if (timer == 1) { settledAt = null; dustAt.clear() }
                if (inFluid(hook)) inFluidTicks++
                // settled: remember where; then a slide of 2.5+ blocks means it caught on a mob
                if (inFluidTicks == 20) settledAt = hook.position()
                val s0 = settledAt
                if (driftRecast.value && s0 != null && hook.hookedIn == null && ! bitten(hook) &&
                    kotlin.math.hypot(hook.x - s0.x, hook.z - s0.z) > 2.5) {
                    statDrift++
                    journal.log("hook drifted ${"%.1f".format(kotlin.math.hypot(hook.x - s0.x, hook.z - s0.z))} blocks, recasting")
                    hold(rod); use(p)
                    state = State.CAST
                    wait = castWait()
                    status = "hook drifted off, recasting"
                    return
                }
                // a burst of dust at the hook = it didn't go in (after the cast's own splash, not too often)
                while (dustAt.isNotEmpty() && tickCount - dustAt.first() > 20) dustAt.removeFirst()
                if (particleRecast.value && timer > 30 && dustAt.size >= 8 && tickCount - lastParticleRecast > 60 && ! waitingMarker(hook)) {
                    lastParticleRecast = tickCount
                    journal.log("splash particles at the hook (${dustAt.size}), recasting")
                    statSplash++
                    dustAt.clear()
                    hold(rod); use(p)
                    state = State.CAST
                    wait = castWait()
                    status = "hook not in the ${fluidName()}, recasting"
                    return
                }
                if (inFluidTicks == 8) lastLanding = hook.position()
                status = "waiting ${timer / 20}s in ${fluidName()}" + (if (hotspot != null) " (hotspot)" else "") + (if (mineAlive.isNotEmpty()) " - ${mineAlive.size} alive" else "")
                if (hotspots.value) {
                    val hs = hotspot
                    val moved = hs != null && hotspotAt != null && hs.position().distanceTo(hotspotAt!!) > 3.0
                    val gone = hs != null && (! hs.isAlive || hs.isRemoved || p.level().getEntity(hs.id) !== hs)
                    // every 5 s: is there a closer / new hotspot in sight while ours isn't (e.g. it moved as a new entity)
                    val elsewhere = hs != null && timer % 100 == 0 && nearestHotspot(p)?.let { it !== hs && it.position().distanceTo(hs.position()) > 3.0 && hs.distanceTo(p) > 20f } == true
                    if (moved || gone || elsewhere) { // the hotspot ended or moved: go after it
                        if (hs != null) badHotspots -= hs.id
                        info(if (moved) "&7The hotspot moved, following it." else "&7Hotspot gone, looking for the next one.")
                        hold(rod); use(p)
                        hotspot = null
                        state = State.FIND
                        status = "hotspot gone"
                        return
                    }
                    if (hs != null && inFluidTicks == 8 && ! landedIn(p, hook, hs)) {
                        hold(rod); use(p)
                        state = State.CAST
                        wait = castWait()
                        return
                    }
                    if (hs == null && timer % 200 == 0 && nearestHotspot(p) != null) { // one appeared
                        hold(rod); use(p)
                        state = State.FIND
                        return
                    }
                }
                if (bitten(hook)) {
                    if (tickCount < guiLockUntil) return // just closed a menu: not yet
                    if (slugfish.value && timer < (if (slugPet.value) 220 else 420)) return // Slugfish bite only after 20 s
                    biteAt = hook.position()
                    journal.log("bite after ${timer / 20.0}s  (${fluidName()})")
                    state = State.REEL
                    wait = delay(reelMin.value, reelMax.value)
                    if (com.github.noamm9.utils.HumanMotion.chance(lateReel.value, RANDOM)) {
                        val late = com.github.noamm9.utils.HumanMotion.between(lateReelTicks.value / 2, lateReelTicks.value, RANDOM)
                        wait += late
                        journal.log("late reel +$late ticks")
                    }
                    status = "bite!"
                    groundFails = 0
                    return
                }
                if (groundRecast.value && grounded(hook)) {
                    hold(rod)
                    use(p)
                    state = State.CAST
                    wait = castWait()
                    statGround++
                    if (++groundFails >= 3) return stop("the hook keeps landing on the ground - look at the ${fluidName()}")
                    status = "hook on the ground, recasting ($groundFails/3)"
                    return
                }
                if (timer > castTimeout.value * 20) {
                    hold(rod)
                    use(p)
                    state = State.CAST
                    wait = castWait()
                    status = "nothing bit, recasting"
                }
            }

            State.REEL -> {
                hold(rod)
                // where the creature will come up: the hook now, or where it was at the bite if it's gone already
                val at = hook?.position() ?: biteAt
                if (at != null) {
                    reels += Reel(tickCount, at)
                    if (reels.size > 6) reels.removeFirst()
                }
                if (hook != null) use(p)
                biteAt = null
                state = State.CAST
                wait = castWait()
                status = "reeled in"
            }

            State.FIGHT, State.FIND, State.TRAVEL, State.SELL, State.BREAK, State.RESUME -> {}
        }
    }

    // ------------------------------------------------------------------ walking state (AutoFishingTravel.kt)

    internal var walkSteer = false
    internal var walkGoalYaw = 0f
    internal var walkGoalPitch = 0f
    internal var walkPitch = 0f

    // ------------------------------------------------------------------ long breaks

    internal fun scheduleBreak() {
        val lo = minOf(breakEveryMin.value, breakEveryMax.value)
        val hi = maxOf(breakEveryMin.value, breakEveryMax.value)
        nextBreakAt = System.currentTimeMillis() + timing.uniform(lo * 60, hi * 60) * 1000L
    }

    /** On a break: stand still, now and then look around a little, then back to fishing. */
    internal fun onBreak(p: LocalPlayer) {
        val left = breakUntil - System.currentTimeMillis()
        status = "on a break (${left / 60000}:${"%02d".format((left / 1000) % 60)})"
        if (left <= 0) {
            scheduleBreak()
            offYaw = 0f; offPitch = 0f; moveLen = 0
            state = State.CAST
            returning = true
            wait = castWait()
            info("&7Break over, back to fishing.")
            return
        }
        if (--glanceIn <= 0) {
            // look somewhere else for a while, then back
            toYaw = (RANDOM.nextFloat() * 2 - 1) * 40f
            toPitch = - 10f + RANDOM.nextFloat() * 25f
            fromYaw = offYaw; fromPitch = offPitch
            moveTick = 0
            moveLen = timing.uniform(14, 30)
            glanceIn = timing.uniform(80, 260)
        }
        if (moveLen > 0) {
            moveTick++
            val t = (moveTick / moveLen.toFloat()).coerceAtMost(1f)
            val ease = t * t * (3 - 2 * t)
            offYaw = fromYaw + (toYaw - fromYaw) * ease
            offPitch = fromPitch + (toPitch - fromPitch) * ease
            com.github.noamm9.utils.Humanize.glide(p, anchorYaw + offYaw + wobYaw, (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f))
            if (t >= 1f) moveLen = 0
        }
    }

    // ------------------------------------------------------------------ tracking state (AutoFishingCombat.kt)

    internal var track: LivingEntity? = null
    internal var trackLoose = false
    internal var trackSettle = 0
    internal var trackVelYaw = 0f
    internal var trackVelPitch = 0f
    internal var trackLastNs = 0L
    internal var trackOff = Vec3.ZERO       // where on the body we aim; drifts slowly, not a new point every tick
    internal var trackOffGoal = Vec3.ZERO
    internal var trackOffNext = 0L
    internal var trackMaxSpeed = 300f
}
//#endif
