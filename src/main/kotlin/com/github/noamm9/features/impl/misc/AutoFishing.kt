package com.github.noamm9.features.impl.misc

//#if CHEAT
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
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
 */
object AutoFishing: Feature("Fishes by itself in water or lava: casts, reels in on the bite, recasts and kills sea creatures as soon as they come up. Human-like delays and smooth head movement (Reel Assist style).") {
    private val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Look at the water or lava and press it to start, press again to stop")
    private val fluid by DropdownSetting("Fluid", 0, listOf("Auto", "Water", "Lava")).withDescription("Auto: works out water or lava from where the hook lands. Only used to tell a hook that fell on the ground")

    private val reelMin by SliderSetting("Reel Delay Min", 2, 1, 17, 1, " ticks").section("Timing").withDescription("Shortest wait after the bite before reeling in")
    private val reelMax by SliderSetting("Reel Delay Max", 9, 1, 17, 1, " ticks").withDescription("Longest wait after the bite (17 at most, so the fish doesn't get away)")
    private val castMin by SliderSetting("Cast Delay Min", 6, 1, 60, 1, " ticks").withDescription("Shortest wait before casting again")
    private val castMax by SliderSetting("Cast Delay Max", 34, 1, 80, 1, " ticks")
    private val hesitate by SliderSetting("Hesitation", 12, 0, 40, 1, "%").withDescription("Chance that a cast comes a little later than usual (0.3-1 s more)")
    private val aimWobble by SliderSetting("Cast Aim Wobble", 0.5, 0.0, 2.0, 0.1, "°").withDescription("Each cast is aimed a tiny bit differently, like a hand that never lands on the exact same spot")
    private val timingModel by DropdownSetting("Timing Model", 2, listOf("Even", "Reel Assist", "Human")).withDescription(
        "Even: any tick in the range equally. Reel Assist: mostly the lower third with the odd slow reaction. Human: skewed reaction times, a rhythm that drifts over minutes, each delay leaning on the last one, slight fatigue over time, no tick count twice in a row"
    )
    private val slipChance by SliderSetting("Slow Reactions", 6, 0, 30, 1, "%").withDescription("Human model: how often a clearly slow reaction happens").showIf { timingModel.value == 2 }
    private val drift by SliderSetting("Rhythm Drift", 20, 0, 50, 1, "%").withDescription("Human model: how far the overall speed wanders over minutes").showIf { timingModel.value == 2 }
    private val fatigue by SliderSetting("Fatigue", 8, 0, 40, 1, "%/h").withDescription("Human model: how much slower reactions get per hour of fishing (up to 2 hours)").showIf { timingModel.value == 2 }
    private val lookAtCatch by ToggleSetting("Look At The Catch", true).withDescription("After a sea creature or a good catch, waits a bit longer before casting again, like reading what came up")
    private val breakChance by SliderSetting("Short Breaks", 2.0, 0.0, 15.0, 0.5, "%").withDescription("Chance per cast of a 2-6 s pause before casting (checking chat, the inventory...)")
    private val castTimeout by SliderSetting("Recast After", 45, 10, 120, 5, " s").withDescription("Reels in and casts again if nothing bites for this long")
    private val groundRecast by ToggleSetting("Recast If Hook Lands On Ground", true).withDescription("Casts again when the hook settles somewhere that isn't water / lava; stops after 3 misses in a row")
    private val hookDive by ToggleSetting("Detect By Hook Dive", true).withDescription("Also reels in when the hook suddenly dives (vanilla bite), not only on the \"!!!\" sign")

    private val lookJitter by ToggleSetting("Look Movement", true).section("Look").withDescription("Small eased head movements around the bobber now and then, like a person holding the rod")
    private val jitterDegrees by SliderSetting("Movement Size", 1.0, 0.2, 4.0, 0.1, "°")
    private val jitterEvery by SliderSetting("Movement Every", 15, 3, 60, 1, " s").withDescription("On average; each wait is 80-120% of this")
    private val smoothTurns by ToggleSetting("Smooth Turning", true).withDescription("Eases every turn (to sea creatures and back). Uses Assist Core's turning when it's installed")

    private val rodSlot by SliderSetting("Rod Slot", 0, 0, 9, 1).section("Items").withDescription("Hotbar slot (1-9) of the rod. 0 = first fishing rod in the hotbar")
    private val killCreatures by ToggleSetting("Kill Sea Creatures", true).section("Sea Creatures")
    private val killRange by SliderSetting("Detect Range", 14.0, 4.0, 24.0, 0.5, " blocks").withDescription("A sea creature that comes up within this range is killed right away")
    private val blastRange by SliderSetting("Hyperion Reach", 6.0, 3.0, 10.0, 0.5, " blocks").withDescription("Casts once a creature is this close (the blast hits around you)")
    private val onlyMine by ToggleSetting("Only My Sea Creatures", true).withDescription(
        "Only fights creatures that came up at your hook right after you reeled in (and anything that hits you). Other people's creatures are left alone"
    )
    private val ownWindow by SliderSetting("My Creature Window", 1.0, 0.3, 3.0, 0.1, " s").withDescription("A creature counts as yours only if it appears within this long after YOU reel in. Lower it if other people's creatures get picked up")
    private val ownDistance by SliderSetting("My Creature Distance", 4.0, 1.5, 8.0, 0.5, " blocks").withDescription("...and only this close to where your hook was")
    private val noticeMin by SliderSetting("Notice Delay Min", 8, 0, 40, 1, " ticks").withDescription("How long it takes to notice a sea creature that came up, before reacting")
    private val noticeMax by SliderSetting("Notice Delay Max", 26, 0, 60, 1, " ticks")
    private val giveUp by SliderSetting("Give Up After", 5, 1, 30, 1, " s").withDescription("A creature that doesn't come within reach in this time is left alone and fishing goes on")
    private val manaCost by SliderSetting("Mana Per Cast", 300, 0, 1000, 10).withDescription("Wither Impact's mana cost for you. Below it, the Hyperion swings as a sword instead of casting (no more standing there with no mana)")
    private val pauseMana by SliderSetting("Pause Below Mana", 35, 0, 90, 5, "%").withDescription("Stops casting the rod while mana is under this, so no new sea creatures come up until it has refilled (0 = never pause)")
    private val weaponSlot by SliderSetting("Weapon Slot", 0, 0, 9, 1).withDescription("Hotbar slot (1-9) of the weapon. 0 = Hyperion / Astraea / Scylla / Valkyrie if found")
    private val hitDelay by SliderSetting("Hit Delay", 4, 1, 20, 1, " ticks")

    private val hotspots by ToggleSetting("Go To Hotspots", false).section("Hotspots").withDescription(
        "Finds the nearest fishing Hotspot, walks to a spot on the shore next to it and casts into it. When it goes away, moves on to the next one. With none around it fishes where you started"
    )
    private val hopMin by SliderSetting("AOTV Gap Min", 5, 2, 40, 1, " ticks").withDescription("Shortest wait between etherwarps / AOTV hops on the way (same timing model as casting)")
    private val hopMax by SliderSetting("AOTV Gap Max", 18, 2, 60, 1, " ticks")
    private val walkSome by SliderSetting("Walk Instead", 15, 0, 80, 5, "%").withDescription("Chance to walk a stretch instead of hopping, when walking is possible")
    private val aotvRange by SliderSetting("AOTV Distance", 12, 8, 16, 1, " blocks").withDescription("How far your AOTV teleports (8 + Transmission Tuners)")
    private val hotspotRange by SliderSetting("Search Range", 200, 16, 400, 8, " blocks").withDescription("How far it looks for a Hotspot. Hotspots seen earlier are remembered for 3 minutes, even after they're out of sight")
    private val useEther by ToggleSetting("Etherwarp On The Way", true).withDescription("Plans etherwarps (and AOTV hops) to get to far or high spots, checking where each one lands, like Secret Routes does")
    private val showRoute by ToggleSetting("Show Route", true).withDescription("Draws the planned walk and the next etherwarp / AOTV landing in the world")
    private val hotspotRadius by SliderSetting("Hotspot Size", 2.5, 1.0, 6.0, 0.5, " blocks").withDescription("How close to the Hotspot's centre the hook has to land; it adjusts the aim until it does")
    private val stayHome by ToggleSetting("Return To Spot", true).withDescription("Walks back to the fishing spot if you get pushed away from it")

    private val autoSell by ToggleSetting("Auto Sell", false).section("Auto Sell").withDescription(
        "When the inventory is nearly full: opens the SkyBlock Menu (hotbar slot below), clicks the Booster Cookie, sells only the items on your sell list to the NPC, closes it and goes back to fishing"
    )
    private val sellWhenFree by SliderSetting("Sell When Free Slots", 3, 0, 15, 1).withDescription("Sells once this few empty slots are left")
    private val menuSlot by SliderSetting("Menu Slot", 9, 1, 9, 1).withDescription("Hotbar slot of the SkyBlock Menu (the Nether Star)")
    private val sellListKey by KeybindSetting("Add To Sell List Key").withDescription("In any inventory, point at an item and press this to add it to (or remove it from) the sell list. Only items on the list are ever sold")
    val sellList by com.github.noamm9.config.types.TextInputSetting("Sell List", "").withDescription("The items Auto Sell may sell, by name, separated by | (fill it with the key above, or type names here)")
    private val clearSellList by com.github.noamm9.config.types.ButtonSetting("Clear Sell List") {
        sellList.value = ""
        NotificationManager.push("Sell List", "&fCleared")
    }.withDescription("Empties the sell list")

    private val failsafe by ToggleSetting("Stop On Anything Unexpected", true).section("Failsafe").withDescription(
        "Stops (doesn't try to hide anything): when the server teleports or turns you, your view jumps 70 degrees or more in an instant, your held item is changed, or a menu opens by itself"
    )
    private val longBreaks by ToggleSetting("Long Breaks", true).section("Breaks").withDescription("Every so often reels in and takes a proper break, looking around now and then, like a person getting a drink")
    private val breakEveryMin by SliderSetting("Break Every Min", 25, 5, 120, 1, " min").showIf { longBreaks.value }
    private val breakEveryMax by SliderSetting("Break Every Max", 45, 5, 180, 1, " min").showIf { longBreaks.value }
    private val breakLenMin by SliderSetting("Break Length Min", 60, 10, 900, 10, " s").showIf { longBreaks.value }
    private val breakLenMax by SliderSetting("Break Length Max", 240, 10, 1800, 10, " s").showIf { longBreaks.value }
    private val showMarkers by ToggleSetting("Show Markers", true).section("Display").withDescription("A box where the hook lands and a highlight on your sea creatures (works with Kill Sea Creatures off too)")
    private val creatureTracer by ToggleSetting("Creature Tracer", true).withDescription("A line from the middle of your screen to each of your sea creatures")

    private val standCentre by ToggleSetting("Stand In Block Centre", true).section("Safety").withDescription("Before casting and before fighting, steps (sneaking, so it can't walk off an edge) to the exact middle of a safe block: solid ground under it, no lava in or under it")
    private val stopHealth by SliderSetting("Stop Below Health", 30, 0, 90, 5, "%").section("Safety").withDescription("Stops when your health drops below this (0 = never)")
    private val chatInfo by ToggleSetting("Chat Messages", true)
    private val statusHud by ToggleSetting("Status HUD", true)

    private enum class State { FIND, TRAVEL, CAST, WAIT_BITE, REEL, FIGHT, SELL, BREAK }

    private val RANDOM = Random()
    private var timing = com.github.noamm9.utils.HumanTiming()
    private var hopThisStretch = true
    private var stretchLeft = 0
    private var justCaughtSomething = false
    private val WITHER_BLADES = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
    private val NPC_CLASSES = setOf("Villager", "WanderingTrader")

    private var running = false
    private var state = State.CAST
    private var wait = 0
    private var timer = 0
    private var inFluidTicks = 0
    private var hitCooldown = 0
    private var status = "idle"
    private var lastMobIds = emptySet<Int>()
    private val waiting = HashMap<Int, Int>()
    private val inReach = HashMap<Int, Pair<Int, Float>>() // id -> ticks in reach without losing health, last health
    private var manaWait = 0
    private var fightTicks = 0
    private var blastBlocked = false
    private var centreTicks = 0
    private var centreTarget: Vec3? = null
    private val ignored = HashSet<Int>()
    private var groundTicks = 0
    private var groundFails = 0
    private var lastHookPos: Vec3? = null

    // look: where we fish (anchor) + a small eased offset
    private var anchorYaw = 0f
    private var anchorPitch = 0f
    private var offYaw = 0f
    private var offPitch = 0f
    private var fromYaw = 0f
    private var fromPitch = 0f
    private var toYaw = 0f
    private var toPitch = 0f
    private var moveTick = 0
    private var moveLen = 0
    private var nextMoveIn = 0
    private var returning = false

    // hotspots / location
    private var hotspot: ArmorStand? = null
    private var stand: Vec3? = null
    private val badHotspots = HashSet<Int>()
    private var aimTries = 0
    private var travelStuck = 0
    private var ownMoveTicks = 0
    private var expectedSlot = - 1
    private var lastLookYaw: Float? = null
    private var lastLookPitch = 0f

    // safe walking (own path, avoids lava / water)
    private var path: List<BlockPos> = emptyList()
    private var pathIndex = 0
    private var pathGoal: Vec3? = null
    private var lastSafe: Vec3? = null
    private var lavaTicks = 0
    private var hopWait = 0
    private var repath = 0

    // selling
    private var sellStep = 0
    private var sellTimer = 0
    private var sellGap = 0
    private var sellTitle: String? = null
    private val sellQueue = ArrayDeque<Int>()
    private var sellFails = 0
    private var sold = 0

    // planned hops (etherwarp / AOTV) while travelling
    private class Hop(val ether: Boolean, val slot: Int, val target: BlockPos?, val yaw: Float, val pitch: Float, val landing: Vec3)
    private var hop: Hop? = null
    private var hopPhase = 0
    private var hopTimer = 0
    private var hopFrom: Vec3 = Vec3.ZERO
    private var planIn = 0
    private val remembered = HashMap<Long, Pair<Vec3, Long>>() // block key -> position, last seen
    private var scouting = false

    // aiming the cast
    private var aimTarget: Vec3? = null
    /** This cast's small hand wobble on top of the aim. */
    private var wobYaw = 0f
    private var wobPitch = 0f

    // session numbers for the status card
    private var sessionStart = 0L
    private var kills = 0
    private var creaturesCaught = 0
    private val mineAlive = HashSet<Int>()
    private var lastLanding: Vec3? = null
    private var nextBreakAt = 0L
    private var breakUntil = 0L
    private var glanceIn = 0

    // which creatures are ours: they come up at our hook right after we reel in
    private class Reel(val tick: Int, val at: Vec3)
    private val reels = ArrayDeque<Reel>()
    private val firstSeen = HashSet<Int>()
    private val mine = HashSet<Int>()
    private val seenAt = HashMap<Int, Int>()
    private val seenPos = HashMap<Int, Vec3>()

    // noticing sea creatures
    private val noticeAt = HashMap<Int, Int>()
    private var tickCount = 0
    private var fightWait = 0
    private var castDownPitch = 0f
    private var hypeStage = 0
    private var hypeBeat = 0

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) runCatching(::tick).onFailure { stop("error: ${it.message}") }
        }
        register<WorldChangeEvent> {
            remembered.clear()
            if (running) stop("world changed")
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
                    val c = com.github.noamm9.ui.clickgui.components.settings.Style.accent2
                    // interpolated so the box doesn't lag behind a moving mob
                    val at = e.getPosition(pt)
                    val bb = e.boundingBox.move(at.subtract(e.position())).inflate(0.1)
                    event.ctx.renderBoxBounds(bb, c, c.withAlpha(45), phase = true, lineWidth = 2)
                    if (creatureTracer.value) event.ctx.renderTracer(Vec3(at.x, bb.minY + (bb.maxY - bb.minY) / 2, at.z), c, 1.5)
                    val tag = tagOf(e) ?: e.name.string
                    val dist = mc.player?.distanceTo(e)?.toInt() ?: 0
                    event.ctx.renderString("$tag §7${dist}m", at.x, bb.maxY + 0.6, at.z, java.awt.Color.WHITE, 1.1f, phase = true)
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
            if (! running || ! failsafe.value || ownMoveTicks > 0) return@register
            val name = event.packet.javaClass.simpleName
            if (event.packet is ClientboundPlayerPositionPacket || name.contains("PlayerRotation")) mc.execute { if (running) failStop("the server moved or turned you") }
        }

        hudElement("Auto Fishing Status", { statusHud.value }, { running }) { ctx, example ->
            drawCard(ctx, example)
        }.also { it.themedPanel = false }
    }

    private val fishIcon = net.minecraft.resources.Identifier.fromNamespaceAndPath(com.github.noamm9.NoammAddons.MOD_ID, "textures/gui/icons/fishing.png")

    private fun stateLabel() = when (state) {
        State.FIND -> "Looking for a hotspot"
        State.TRAVEL -> "Travelling"
        State.CAST -> if (returning) "Re-aiming" else "Casting"
        State.WAIT_BITE -> "Waiting"
        State.REEL -> "Reeling"
        State.FIGHT -> "Attacking"
        State.SELL -> "Selling"
        State.BREAK -> "On a break"
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "${s / 3600}h ${(s / 60) % 60}m" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
    }

    /** The status card: icon, state, kills per hour big, then sea creatures / session / per hour / next break. */
    private fun drawCard(ctx: net.minecraft.client.gui.GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        val st = com.github.noamm9.ui.clickgui.components.settings.Style
        val w = 150f
        val h = 92f
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
        return w to h
    }

    override fun onDisable() {
        super.onDisable()
        if (running) stop(null)
    }

    private fun info(msg: String) {
        if (chatInfo.value) modMessage(msg)
    }

    private fun start() {
        val p = mc.player ?: return
        if (rod() == null) return info("&cNo fishing rod in the hotbar.")
        anchorYaw = p.yRot
        anchorPitch = p.xRot
        seenAt.clear(); seenPos.clear(); reels.clear()
        tickCount += 100 // anything seen from now on is "after" any stale reel
        timing = com.github.noamm9.utils.HumanTiming()
        lastLookYaw = null
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
        state = if (hotspots.value) State.FIND else State.CAST
        stand = if (hotspots.value) null else p.position()
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

    private fun failStop(why: String) {
        stop("stopped: $why")
        NotificationManager.push("Auto Fishing stopped", why)
        com.github.noamm9.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 0.6f)
    }

    private fun stop(reason: String?) {
        if (running) releaseCentre()
        centreTarget = null
        running = false
        hop = null
        AutoRoutes.releaseAll()
        mc.options.keyUse.isDown = false
        if (reason != null) info("&eAuto Fishing $reason.")
    }

    // ------------------------------------------------------------------ timing

    private fun rand(min: Int, max: Int) = if (max <= min) min else min + RANDOM.nextInt(max - min + 1)

    /** Reel Assist's delay: mostly the lower third of [min, max], 8% of the time a slow reaction. */
    private fun delay(min: Int, max: Int): Int {
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
    private fun castWait(): Int {
        var t = delay(castMin.value, castMax.value)
        if (lookAtCatch.value && justCaughtSomething) t += timing.uniform(8, 25)
        justCaughtSomething = false
        t += timing.maybeBreak(breakChance.value, 40, 120)
        if (RANDOM.nextInt(100) < hesitate.value) t += timing.uniform(6, 20)
        return t
    }

    private fun nextMoveDelay() = (jitterEvery.value * 20 * (0.8 + 0.4 * RANDOM.nextDouble())).toInt()

    // ------------------------------------------------------------------ turning

    private fun wrap(d: Float): Float {
        var x = d % 360f
        if (x > 180f) x -= 360f
        if (x < - 180f) x += 360f
        return x
    }

    /** Eased turn towards yaw / pitch. True once within [tol] degrees. */
    private fun turn(p: LocalPlayer, yaw: Float, pitch: Float, tol: Float = 1.5f): Boolean {
        val dy = wrap(yaw - p.yRot)
        val dp = pitch - p.xRot
        if (abs(dy) <= tol && abs(dp) <= tol) return true
        if (! smoothTurns.value) {
            PlayerUtils.rotate(yaw, pitch)
            return true
        }
        return com.github.noamm9.utils.Humanize.turnTo(p, yaw, pitch, tol)
    }

    /** The small eased head movements around the bobber while waiting. Also follows the player's own camera moves. */
    private fun lookMovement(p: LocalPlayer) {
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
            p.yRot = anchorYaw + offYaw + wobYaw
            p.xRot = (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f)
            if (t >= 1f) moveLen = 0
            return
        }
        if (--nextMoveIn > 0) return
        val d = jitterDegrees.value.toFloat()
        fromYaw = offYaw; fromPitch = offPitch
        toYaw = (RANDOM.nextFloat() * 2 - 1) * d
        toPitch = (RANDOM.nextFloat() * 2 - 1) * d * 0.6f
        moveTick = 0
        moveLen = rand(6, 14)
        nextMoveIn = nextMoveDelay()
    }

    // ------------------------------------------------------------------ main loop

    private fun rod(): Int? {
        if (rodSlot.value in 1 .. 9) return rodSlot.value - 1
        return PlayerUtils.findHotbarSlot { it.item == Items.FISHING_ROD }
    }

    private fun hold(slot: Int) {
        val p = mc.player ?: return
        if (p.inventory.selectedSlot != slot) p.inventory.selectedSlot = slot
        expectedSlot = slot
    }

    private fun use(p: LocalPlayer) {
        mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
        p.swing(InteractionHand.MAIN_HAND)
    }

    private fun tick() {
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
        if (stayHome.value && home != null && state != State.FIGHT && p.position().distanceTo(home) > 3.0) {
            if (p.fishing != null) rod()?.let { hold(it); use(p) }
            state = State.TRAVEL
            path = emptyList()
            status = "pushed away, going back"
            return
        }

        claimOwn(p)
        // sea creatures first, the moment one comes up
        val seen = if (killCreatures.value && mc.gui.screen() == null) creatures(p) else emptyList()
        if (mineAlive.isNotEmpty()) mineAlive.removeIf { id -> val e = p.level().getEntity(id); (e == null || ! e.isAlive).also { if (it && e != null) kills++ } }
        // a person needs a moment to notice something new: each creature gets its own notice time
        for (m in seen) noticeAt.getOrPut(m.id) { tickCount + delay(noticeMin.value, noticeMax.value) }
        if (noticeAt.size > 200) noticeAt.keys.retainAll(seen.map { it.id }.toSet())
        val mobs = seen.filter { (noticeAt[it.id] ?: 0) <= tickCount }
        if (state == State.FIGHT || mobs.isNotEmpty()) {
            if (mobs.isEmpty()) {
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
                fightWait = delay(3, 10) // then a beat before turning round
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
            p.yRot = anchorYaw + offYaw + wobYaw // land exactly, so it isn't taken for a camera move
            p.xRot = (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f)
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
                // settle on this cast's aim (with its wobble) first, then land on it exactly
                val ty = anchorYaw + offYaw + wobYaw
                val tp = (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f)
                if (! turn(p, ty, tp, 0.35f)) return
                p.yRot = ty
                p.xRot = tp
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
                if (inFluid(hook)) inFluidTicks++
                if (inFluidTicks == 8) lastLanding = hook.position()
                status = "waiting ${timer / 20}s in ${fluidName()}" + if (hotspot != null) " (hotspot)" else ""
                if (hotspots.value) {
                    val hs = hotspot
                    if (hs != null && ! hs.isAlive) { // the hotspot ended: next one
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
                    state = State.REEL
                    wait = delay(reelMin.value, reelMax.value)
                    status = "bite!"
                    groundFails = 0
                    return
                }
                if (groundRecast.value && grounded(hook)) {
                    hold(rod)
                    use(p)
                    state = State.CAST
                    wait = castWait()
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
                if (hook != null) {
                    reels += Reel(tickCount, hook.position())
                    if (reels.size > 6) reels.removeFirst()
                    use(p)
                }
                state = State.CAST
                wait = castWait()
                status = "reeled in"
            }

            State.FIGHT, State.FIND, State.TRAVEL, State.SELL, State.BREAK -> {}
        }
    }

    private fun wantsLava(hook: FishingHook?) = when (fluid.value) {
        1 -> false
        2 -> true
        else -> hook != null && (hook.isInLava || nearFluid(hook, FluidTags.LAVA))
    }

    private fun fluidName() = if (wantsLava(mc.player?.fishing)) "lava" else "water"

    /** The bobber floats on top, so its own in-water flag flickers: look at its block and the two below too. */
    private fun nearFluid(hook: FishingHook, tag: net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>): Boolean {
        val pos = hook.blockPosition()
        for (dy in 0 downTo - 2) if (hook.level().getFluidState(pos.offset(0, dy, 0)).`is`(tag)) return true
        return false
    }

    private fun inFluid(hook: FishingHook) = when (fluid.value) {
        1 -> hook.isInWater || nearFluid(hook, FluidTags.WATER)
        2 -> hook.isInLava || nearFluid(hook, FluidTags.LAVA)
        else -> hook.isInWater || hook.isInLava || nearFluid(hook, FluidTags.WATER) || nearFluid(hook, FluidTags.LAVA)
    }

    /** True once the hook has clearly settled somewhere that isn't water / lava. */
    private fun grounded(hook: FishingHook): Boolean {
        if (hook.hookedIn == null && inFluid(hook)) {
            groundTicks = 0
            return false
        }
        val moved = lastHookPos?.distanceToSqr(hook.position()) ?: 1.0
        lastHookPos = hook.position()
        if (timer < 25) return false
        val stuck = hook.hookedIn != null || hook.onGround() || moved < 1.0E-4
        groundTicks = if (stuck) groundTicks + 1 else 0
        return groundTicks >= 10
    }

    /** SkyBlock puts a "!!!" stand over the hook on a bite (water and lava); vanilla pulls the hook down. */
    private fun bitten(hook: FishingHook): Boolean {
        val sign = hook.level().getEntitiesOfClass(ArmorStand::class.java, hook.boundingBox.inflate(1.5, 3.0, 1.5)) {
            it.customName?.string?.contains("!!!") == true
        }
        if (sign.isNotEmpty()) return true
        return hookDive.value && inFluidTicks > 10 && hook.deltaMovement.y < - 0.05
    }

    // ------------------------------------------------------------------ hotspots

    private fun nearestHotspot(p: LocalPlayer): ArmorStand? {
        val r = hotspotRange.value.toDouble()
        val live = p.level().getEntitiesOfClass(ArmorStand::class.java, p.boundingBox.inflate(r, 64.0, r)) {
            it.isAlive && it.customName?.string?.contains("HOTSPOT", ignoreCase = true) == true
        }
        val now = System.currentTimeMillis()
        for (h in live) remembered[h.blockPosition().asLong()] = h.position() to now
        return live.filter { it.id !in badHotspots }.minByOrNull { it.distanceToSqr(p) }
    }

    /** A hotspot seen earlier that is out of entity range now (remembered for 3 minutes). */
    private fun rememberedHotspot(p: LocalPlayer): Vec3? {
        val now = System.currentTimeMillis()
        remembered.entries.removeIf { now - it.value.second > 180_000 }
        return remembered.values.map { it.first }.filter { it.distanceTo(p.position()) > 40 }.minByOrNull { it.distanceTo(p.position()) }
    }

    private fun findHotspot(p: LocalPlayer) {
        val hs = nearestHotspot(p)
        if (hs == null) {
            // none in sight: head for one seen earlier, it'll show up once we're close
            val far = rememberedHotspot(p)
            val spot = far?.let { shoreSpot(p, it) }
            if (far != null && spot != null) {
                hotspot = null
                stand = spot
                scouting = true
                path = emptyList()
                travelStuck = 0
                state = State.TRAVEL
                info("&7Heading for a hotspot seen earlier &8(${p.position().distanceTo(far).toInt()} blocks)")
                return
            }
            if (far != null) remembered.remove(BlockPos.containing(far).asLong())
            hotspot = null
            if (stand == null) stand = p.position()
            status = "no hotspot nearby, fishing here"
            state = State.CAST
            return
        }
        scouting = false
        val spot = shoreSpot(p, hs.position())
        if (spot == null) {
            badHotspots += hs.id
            status = "no place to stand by that hotspot"
            return
        }
        hotspot = hs
        stand = spot
        aimTries = 0
        travelStuck = 0
        path = emptyList()
        state = State.TRAVEL
        info("&7Hotspot found &8(${p.position().distanceTo(hs.position()).toInt()} blocks)&7, going there.")
    }

    /** A block on the shore 4-10 blocks from the hotspot, with a clear view of it, as close to us as possible. */
    private fun shoreSpot(p: LocalPlayer, centre: Vec3): Vec3? {
        val level = p.level()
        val c = BlockPos.containing(centre)
        var best: Vec3? = null
        var bestScore = Double.MAX_VALUE
        for (dx in - 10 .. 10) for (dz in - 10 .. 10) {
            val flat = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
            if (flat < 4.0 || flat > 10.0) continue
            for (dy in - 2 .. 5) {
                val feet = BlockPos(c.x + dx, c.y + dy, c.z + dz)
                val below = feet.below()
                if (level.getBlockState(below).getCollisionShape(level, below).isEmpty) continue
                if (! level.getFluidState(below).isEmpty) continue
                if (! level.getBlockState(feet).getCollisionShape(level, feet).isEmpty || ! level.getFluidState(feet).isEmpty) continue
                if (! level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty) continue
                if (nearLava(feet, 1)) continue // never stand right at a lava edge
                val eye = Vec3(feet.x + 0.5, feet.y + 1.62, feet.z + 0.5)
                val clip = level.clip(net.minecraft.world.level.ClipContext(eye, centre.add(0.0, 0.3, 0.0), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
                if (clip.type != net.minecraft.world.phys.HitResult.Type.MISS && clip.location.distanceTo(centre) > 1.5) continue
                val pos = Vec3(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5)
                val score = pos.distanceTo(p.position()) + abs(flat - 7.0) * 2
                if (score < bestScore) { bestScore = score; best = pos }
                break
            }
        }
        return best
    }

    private fun travel(p: LocalPlayer) {
        val goal = stand ?: return run { state = State.FIND }
        ownMoveTicks = 20 // our own walking / AOTV: not the server's doing
        expectedSlot = - 1
        if (horizontalDist(p.position(), goal) < 0.6 && abs(p.y - goal.y) < 1.5) {
            stopWalking()
            path = emptyList()
            arrive(p)
            return
        }
        // a hop in progress
        if (hop != null) return doHop(p)
        if (hopWait > 0) hopWait--

        val flat = horizontalDist(p.position(), goal)
        val rise = goal.y - p.y
        if (pathGoal != goal || --repath <= 0) {
            pathGoal = goal
            path = if (flat < 70) findPath(p, BlockPos.containing(goal.x, goal.y + 0.1, goal.z)) ?: emptyList() else emptyList()
            pathIndex = 0
            repath = 100
        }
        // far, high, or no walkable way: plan an etherwarp / AOTV hop, checking where it lands
        val needHop = path.isEmpty() || flat > 14 || rise > 3
        if (useEther.value && needHop && hopWait <= 0 && p.onGround() && --planIn <= 0) {
            planIn = 6
            val walkable = path.isNotEmpty()
            if (! walkable || RANDOM.nextInt(100) >= walkSome.value) planHop(p, goal)?.let {
                hop = it
                hopPhase = 0
                hopTimer = 0
                stopWalking()
                status = (if (it.ether) "etherwarp" else "AOTV") + " (${p.position().distanceTo(goal).toInt()}m left)"
                return doHop(p)
            }
        }
        if (path.isEmpty()) {
            stopWalking()
            if (planIn == 6 && ++travelStuck > 25) {
                hotspot?.let { badHotspots += it.id }
                hotspot = null
                scouting = false
                state = State.FIND
                status = "no safe way there"
            }
            else status = "planning a way there"
            return
        }
        status = "going to ${if (hotspot != null) "the hotspot" else "the spot"} (${p.position().distanceTo(goal).toInt()}m)"
        walkPath(p)
    }

    private fun arrive(p: LocalPlayer) {
        if (scouting) { // got near a remembered hotspot: look for it properly now
            scouting = false
            remembered.clear()
            state = State.FIND
            return
        }
        val hs = hotspot
        if (hs != null) aimAt(p, hs) else aimTarget = null
        offYaw = 0f; offPitch = 0f; moveLen = 0
        returning = true
        state = State.CAST
        wait = castWait()
        status = "at the spot"
    }

    private fun horizontalDist(a: Vec3, b: Vec3) = kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

    private fun stopWalking() {
        mc.options.keyUp.isDown = false
        mc.options.keyJump.isDown = false
        mc.options.keySprint.isDown = false
    }

    private fun isLava(pos: BlockPos) = mc.level?.getFluidState(pos)?.`is`(FluidTags.LAVA) == true
    private fun isFluid(pos: BlockPos) = mc.level?.getFluidState(pos)?.isEmpty == false

    /** Lava within [r] blocks around (and just below) the feet. */
    private fun nearLava(feet: BlockPos, r: Int): Boolean {
        for (dx in - r .. r) for (dz in - r .. r) for (dy in - 2 .. 0) if (isLava(feet.offset(dx, dy, dz))) return true
        return false
    }

    private fun standable(feet: BlockPos): Boolean {
        val level = mc.level ?: return false
        val below = feet.below()
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty || isFluid(below)) return false
        if (! level.getBlockState(feet).getCollisionShape(level, feet).isEmpty || isFluid(feet)) return false
        val head = feet.above()
        return level.getBlockState(head).getCollisionShape(level, head).isEmpty && ! isFluid(head)
    }

    /** A* over standable blocks: never steps into or next to lava, keeps a block's distance from edges when it can. */
    private fun findPath(p: LocalPlayer, goal: BlockPos): List<BlockPos>? {
        val start = p.blockPosition()
        if (start.distManhattan(goal) > 160) return null
        data class N(val pos: BlockPos, val g: Double, val f: Double)
        val open = java.util.PriorityQueue<N>(compareBy { it.f })
        val came = HashMap<BlockPos, BlockPos>()
        val best = HashMap<BlockPos, Double>()
        fun h(b: BlockPos) = kotlin.math.sqrt(b.distSqr(goal))
        open += N(start, 0.0, h(start))
        best[start] = 0.0
        var expanded = 0
        val dirs = listOf(1 to 0, - 1 to 0, 0 to 1, 0 to - 1, 1 to 1, 1 to - 1, - 1 to 1, - 1 to - 1)
        while (open.isNotEmpty() && expanded++ < 6000) {
            val cur = open.poll()
            if (cur.pos.distManhattan(goal) <= 1 && abs(cur.pos.y - goal.y) <= 1) {
                val out = ArrayList<BlockPos>()
                var c: BlockPos? = cur.pos
                while (c != null) { out += c; c = came[c] }
                out.reverse()
                return out
            }
            if (cur.g > (best[cur.pos] ?: Double.MAX_VALUE)) continue
            for ((dx, dz) in dirs) {
                for (dy in intArrayOf(0, 1, - 1, - 2, - 3)) {
                    val n = cur.pos.offset(dx, dy, dz)
                    if (! standable(n)) continue
                    if (dx != 0 && dz != 0 && (! standable(cur.pos.offset(dx, 0, 0)) || ! standable(cur.pos.offset(0, 0, dz)))) continue
                    if (dy == 1 && ! mc.level!!.getBlockState(cur.pos.above(2)).getCollisionShape(mc.level!!, cur.pos.above(2)).isEmpty) continue
                    if (nearLava(n, 0)) continue
                    val step = (if (dx != 0 && dz != 0) 1.414 else 1.0) + (if (dy != 0) 0.5 else 0.0) + (if (nearLava(n, 1)) 6.0 else 0.0)
                    val g = cur.g + step
                    if (g < (best[n] ?: Double.MAX_VALUE)) {
                        best[n] = g
                        came[n] = cur.pos
                        open += N(n, g, g + h(n))
                    }
                    break // one height per direction (the first that fits)
                }
            }
        }
        return null
    }

    /** Walks the path node to node with eased turns; hops along long safe straight stretches with the AOTV. */
    private fun walkPath(p: LocalPlayer) {
        if (pathIndex >= path.size) return
        // skip nodes we've already passed
        while (pathIndex < path.size - 1 && horizontalDist(p.position(), Vec3.atBottomCenterOf(path[pathIndex])) < 0.7) pathIndex++
        val node = path[pathIndex]
        val target = Vec3.atBottomCenterOf(node)

        // safety: never walk into a block that has lava in it or under it
        val ahead = BlockPos.containing(p.x + p.deltaMovement.x * 4, p.y - 0.5, p.z + p.deltaMovement.z * 4)
        if (isLava(ahead) || isLava(ahead.above()) || (mc.level!!.getBlockState(ahead).isAir && isLava(ahead.below()))) {
            stopWalking()
            repath = 0
            status = "lava ahead, finding another way"
            return
        }

        if (hopWait > 0) hopWait--
        if (tryHop(p)) return

        val yaw = Math.toDegrees(kotlin.math.atan2(target.z - p.z, target.x - p.x)).toFloat() - 90f
        turn(p, yaw, 8f + RANDOM.nextFloat() * 6f, 12f)
        val dy = node.y - p.blockPosition().y
        mc.options.keyUp.isDown = abs(wrap(yaw - p.yRot)) < 50f
        mc.options.keyJump.isDown = p.onGround() && (dy > 0 || p.horizontalCollision)
        mc.options.keySprint.isDown = path.size - pathIndex > 4 && ! nearLava(p.blockPosition(), 2)
    }

    /** AOTV to a path node 9-12 blocks ahead when the stretch is straight, level, in sight and away from lava. */
    private fun tryHop(p: LocalPlayer): Boolean {
        if (hopWait > 0 || ! p.onGround() || RANDOM.nextInt(100) < walkSome.value) return false
        val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "ASPECT_OF_THE_VOID" || it.skyblockId == "ASPECT_OF_THE_END" } ?: return false
        val from = p.position()
        var pick = - 1
        for (i in pathIndex until path.size) {
            val d = horizontalDist(from, Vec3.atBottomCenterOf(path[i]))
            if (d > 12.0) break
            if (abs(path[i].y - p.blockPosition().y) > 1) break
            if (d >= 9.0) pick = i
        }
        if (pick < 0) return false
        val node = path[pick]
        if (nearLava(node, 1)) return false
        // every node on the way has to sit on the straight line (no corner to cut over a gap)
        val end = Vec3.atBottomCenterOf(node)
        for (i in pathIndex .. pick) {
            val c = Vec3.atBottomCenterOf(path[i])
            val t = ((c.x - from.x) * (end.x - from.x) + (c.z - from.z) * (end.z - from.z)) / ((end.x - from.x).let { it * it } + (end.z - from.z).let { it * it }).coerceAtLeast(0.01)
            val px = from.x + (end.x - from.x) * t
            val pz = from.z + (end.z - from.z) * t
            if (kotlin.math.sqrt((c.x - px) * (c.x - px) + (c.z - pz) * (c.z - pz)) > 1.2) return false
        }
        val aim = end.add(0.0, 1.0, 0.0)
        val clip = p.level().clip(net.minecraft.world.level.ClipContext(p.eyePosition, aim, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
        if (clip.type != net.minecraft.world.phys.HitResult.Type.MISS) return false
        hold(slot)
        stopWalking()
        val rot = MathUtils.calcYawPitch(aim)
        if (! turn(p, rot.yaw, rot.pitch, 2f)) return true
        use(p)
        hopWait = delay(hopMin.value, hopMax.value)
        pathIndex = pick
        return true
    }

    // ------------------------------------------------------------------ hop planning (etherwarp / AOTV)

    /** How good standing at [feet] is on the way to [goal] (lower is better). */
    private fun hopScore(feet: Vec3, goal: Vec3) = horizontalDist(feet, goal) + abs(goal.y - feet.y) * 1.2

    /**
     * Picks the next hop towards [goal]:
     * etherwarp - top faces of solid ground near the line to the goal (and the goal itself), each checked with
     * Noamm's etherwarp prediction from a sneaking eye, never next to lava;
     * AOTV - a few aims towards the goal, each traced to where the teleport stops and where we'd fall to.
     * Only hops that get clearly closer count.
     */
    private fun planHop(p: LocalPlayer, goal: Vec3): Hop? {
        val here = hopScore(p.position(), goal)
        val etherSlot = PlayerUtils.findHotbarSlot { com.github.noamm9.utils.items.EtherwarpHelper.getEtherwarpDistance(it) != null }
        if (etherSlot != null) {
            val range = com.github.noamm9.utils.items.EtherwarpHelper.getEtherwarpDistance(p.inventory.getItem(etherSlot)) ?: 57.0
            val sneakFeet = if (p.isCrouching) p.position() else p.position().subtract(0.0, 0.35, 0.0)
            val cands = LinkedHashSet<BlockPos>()
            cands += BlockPos.containing(goal.x, goal.y - 0.5, goal.z)
            val dir = goal.subtract(p.position())
            for (step in 1 .. 14) {
                val t = step / 14.0
                val pt = p.position().add(dir.scale(t))
                for (ox in - 4 .. 4 step 2) for (oz in - 4 .. 4 step 2) {
                    topBlock(BlockPos.containing(pt.x + ox, maxOf(pt.y, goal.y) + 6, pt.z + oz), 16)?.let { cands += it }
                }
            }
            var best: Hop? = null
            var bestScore = here - 6.0
            var checked = 0
            for (b in cands) {
                if (checked > 220) break
                val feet = b.above()
                if (! standable(feet) || nearLava(feet, 1)) continue
                val top = Vec3(b.x + 0.5, b.y + 0.98, b.z + 0.5)
                if (top.distanceTo(p.eyePosition) > range) continue
                val score = hopScore(Vec3.atBottomCenterOf(feet), goal)
                if (score >= bestScore) continue
                checked++
                val rot = MathUtils.calcYawPitch(top)
                val look = Vec3.directionFromRotation(rot.pitch, rot.yaw)
                val land = com.github.noamm9.utils.items.EtherwarpHelper.getEtherPos(sneakFeet, look, range)
                if (! land.succeeded || land.pos != b) continue
                bestScore = score
                best = Hop(true, etherSlot, b, rot.yaw, rot.pitch, Vec3.atBottomCenterOf(feet))
            }
            if (best != null) return best
        }
        val aotvSlot = PlayerUtils.findHotbarSlot { it.skyblockId == "ASPECT_OF_THE_VOID" || it.skyblockId == "ASPECT_OF_THE_END" } ?: return null
        val baseYaw = Math.toDegrees(kotlin.math.atan2(goal.z - p.z, goal.x - p.x)).toFloat() - 90f
        var best: Hop? = null
        var bestScore = here - 4.0
        for (dyaw in floatArrayOf(0f, - 12f, 12f, - 25f, 25f)) for (pitch in floatArrayOf(- 5f, - 20f, - 35f, - 50f, 5f)) {
            val yaw = baseYaw + dyaw
            val land = aotvLanding(p, yaw, pitch) ?: continue
            val score = hopScore(land, goal)
            if (score < bestScore) {
                bestScore = score
                best = Hop(false, aotvSlot, null, yaw, pitch, land)
            }
        }
        return best
    }

    /** The highest solid block with two free blocks over it, scanning down [depth] blocks from [from]. */
    private fun topBlock(from: BlockPos, depth: Int): BlockPos? {
        val level = mc.level ?: return null
        var b = from
        repeat(depth) {
            val below = b.below()
            if (! level.getBlockState(below).getCollisionShape(level, below).isEmpty) return if (isFluid(below)) null else below
            if (isFluid(below)) return null
            b = below
        }
        return null
    }

    /** Where an AOTV with this aim leaves us standing: the teleport stops before the first block, then we fall. Null if unsafe. */
    private fun aotvLanding(p: LocalPlayer, yaw: Float, pitch: Float): Vec3? {
        val look = Vec3.directionFromRotation(pitch, yaw)
        val eye = p.eyePosition
        val end = eye.add(look.scale(aotvRange.value.toDouble()))
        val hit = p.level().clip(net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
        val stop = if (hit.type == net.minecraft.world.phys.HitResult.Type.MISS) end else hit.location.subtract(look.scale(0.6))
        if (stop.distanceTo(eye) < 5.0) return null
        var feet = BlockPos.containing(stop.x, stop.y - 1.6, stop.z)
        repeat(8) { // fall to the ground
            if (isFluid(feet) || isFluid(feet.below())) return null
            if (standable(feet)) return if (nearLava(feet, 1)) null else Vec3.atBottomCenterOf(feet)
            feet = feet.below()
        }
        return null
    }

    private fun doHop(p: LocalPlayer) {
        val h = hop ?: return
        ownMoveTicks = 30
        hold(h.slot)
        when (hopPhase) {
            0 -> { // sneak (etherwarp) or stand (AOTV), turn onto the aim
                PlayerUtils.toggleSneak(h.ether)
                if (! turn(p, h.yaw, h.pitch, 0.6f)) return
                p.yRot = h.yaw; p.xRot = h.pitch
                if (h.ether != p.isCrouching) return // the sneak hasn't reached the server yet
                if (h.ether) { // check once more from where we really are
                    val range = com.github.noamm9.utils.items.EtherwarpHelper.getEtherwarpDistance(p.inventory.getItem(h.slot)) ?: 57.0
                    val land = com.github.noamm9.utils.items.EtherwarpHelper.getEtherPos(p.position(), Vec3.directionFromRotation(h.pitch, h.yaw), range)
                    if (! land.succeeded || land.pos != h.target) { hop = null; PlayerUtils.toggleSneak(false); planIn = 0; return }
                }
                hopTimer = 0
                hopFrom = p.position()
                use(p)
                hopPhase = 1
            }
            else -> { // wait for the teleport
                if (p.position().distanceTo(hopFrom) > 2.0 || ++hopTimer > 15) {
                    PlayerUtils.toggleSneak(false)
                    hop = null
                    hopWait = delay(hopMin.value, hopMax.value)
                    repath = 0
                    planIn = 0
                }
            }
        }
    }

    private fun scheduleBreak() {
        val lo = minOf(breakEveryMin.value, breakEveryMax.value)
        val hi = maxOf(breakEveryMin.value, breakEveryMax.value)
        nextBreakAt = System.currentTimeMillis() + timing.uniform(lo * 60, hi * 60) * 1000L
    }

    /** On a break: stand still, now and then look around a little, then back to fishing. */
    private fun onBreak(p: LocalPlayer) {
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
            p.yRot = anchorYaw + offYaw + wobYaw
            p.xRot = (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f)
            if (t >= 1f) moveLen = 0
        }
    }

    // ------------------------------------------------------------------ auto sell

    private fun sellNames() = sellList.value.split('|').filter { it.isNotBlank() }.toSet()

    private fun itemKey(stack: net.minecraft.world.item.ItemStack): String? =
        if (stack.isEmpty) null else stack.hoverName.string.replace(Regex("§."), "").trim().ifBlank { null }

    private fun freeSlots(p: LocalPlayer) = (0 until 36).count { p.inventory.getItem(it).isEmpty }

    /** Only what's on your sell list is ever sold. */
    private fun shouldSell(stack: net.minecraft.world.item.ItemStack): Boolean {
        if (stack.isEmpty || stack.item == Items.FISHING_ROD) return false
        val name = itemKey(stack) ?: return false
        return name in sellNames()
    }

    /** Inventory indexes (not the hotbar) holding junk. */
    private fun sellables(p: LocalPlayer) = (9 until 36).filter { shouldSell(p.inventory.getItem(it)) }

    private fun sell(p: LocalPlayer) {
        ownMoveTicks = 20
        sellTimer++
        if (sellTimer > 600) return sellDone(p, false, "selling took too long")
        if (sellGap > 0) { sellGap--; return }
        val screen = mc.gui.screen() as? AbstractContainerScreen<*>
        when (sellStep) {
            0 -> { // put the rod away and open the SkyBlock Menu
                if (p.fishing != null) { rod()?.let { hold(it); use(p) }; sellGap = delay(4, 10); return }
                hold(menuSlot.value - 1)
                sellGap = delay(3, 8)
                sellStep = 1
            }
            1 -> {
                use(p)
                sellTimer = 0
                sellStep = 2
            }
            2 -> { // wait for the menu, then click the cookie
                if (screen == null || screen is InventoryScreen) { if (sellTimer > 40) sellDone(p, false, "the SkyBlock Menu didn't open"); return }
                val menu = p.containerMenu
                val top = menu.slots.filter { it.container !is net.minecraft.world.entity.player.Inventory }
                val cookie = top.firstOrNull { it.item.item == Items.COOKIE || it.item.hoverName.string.contains("Cookie", true) }
                    ?: return sellDone(p, false, "no Booster Cookie in the menu")
                sellTitle = screen.title.string
                GuiUtils.clickSlot(cookie.index, GuiUtils.ButtonType.LEFT)
                sellTimer = 0
                sellStep = 3
                sellGap = delay(2, 5)
            }
            3 -> { // the sell menu: queue up the junk
                if (screen == null || screen.title.string == sellTitle) { if (sellTimer > 40) sellDone(p, false, "the sell menu didn't open"); return }
                sellQueue.clear()
                for (slot in p.containerMenu.slots) {
                    if (slot.container !is net.minecraft.world.entity.player.Inventory) continue
                    if (slot.containerSlot < 9) continue // never the hotbar
                    if (shouldSell(slot.item)) sellQueue += slot.index
                }
                sellStep = 4
                sellGap = delay(5, 14) // look at the menu for a moment first
            }
            4 -> { // click them one by one at a human pace
                if (screen == null) return sellDone(p, true, null)
                val next = sellQueue.removeFirstOrNull()
                if (next == null) {
                    sellStep = 5
                    sellGap = delay(4, 12)
                    return
                }
                if (! p.containerMenu.slots.getOrNull(next)?.item.let { it != null && ! it.isEmpty }) return
                GuiUtils.clickSlot(next, GuiUtils.ButtonType.LEFT)
                sold++
                sellGap = delay(4, 11) + if (RANDOM.nextInt(100) < 8) timing.uniform(6, 16) else 0
            }
            else -> sellDone(p, true, null)
        }
    }

    private fun sellDone(p: LocalPlayer, ok: Boolean, why: String?) {
        if (mc.gui.screen() != null) p.closeContainer()
        if (ok) sellFails = 0 else {
            sellFails++
            info("&eAuto Sell: $why${if (sellFails >= 3) " - turned off until the next start" else ""}.")
        }
        expectedSlot = - 1
        rod()?.let { hold(it) }
        state = State.CAST
        returning = true
        wait = castWait()
        status = if (ok) "sold junk ($sold so far)" else "couldn't sell"
    }

    /** In lava: jump and walk back to the last safe ground; stop if it doesn't work out. */
    private fun escapeLava(p: LocalPlayer) {
        ownMoveTicks = 20
        if (p.fishing != null) rod()?.let { hold(it); use(p) }
        val back = lastSafe
        status = "in lava, getting out"
        lavaTicks++
        mc.options.keyJump.isDown = true
        mc.options.keySprint.isDown = true
        if (back != null) {
            // aim at the last safe spot, a little above it so a teleport lands on top
            val target = back.add(0.0, 1.2, 0.0)
            val rot = MathUtils.calcYawPitch(target)
            PlayerUtils.rotate(rot.yaw, rot.pitch)
            mc.options.keyUp.isDown = true
            // swimming alone often can't climb out: teleport back with AOTV, else the Hyperion, every half second
            if (lavaTicks % 10 == 4) {
                val tp = PlayerUtils.findHotbarSlot { it.skyblockId == "ASPECT_OF_THE_VOID" || it.skyblockId == "ASPECT_OF_THE_END" }
                    ?: PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES }
                if (tp != null) { hold(tp); use(p) }
            }
        }
        if (lavaTicks > 200) {
            stopWalking()
            failStop("fell into lava and couldn't get out")
            return
        }
        state = State.TRAVEL
        repath = 0
    }

    /** A block you can safely stand in: solid, non-lava ground under it, room for the body, no lava in it. */
    private fun safeStand(lvl: net.minecraft.world.level.Level, b: BlockPos): Boolean {
        fun solid(x: BlockPos) = ! lvl.getBlockState(x).getCollisionShape(lvl, x).isEmpty
        fun lava(x: BlockPos) = lvl.getFluidState(x).`is`(FluidTags.LAVA)
        val under = b.below()
        return solid(under) && ! lava(under) && ! solid(b) && ! solid(b.above()) && ! lava(b) && ! lava(b.above())
    }

    /**
     * Keeps us in the exact middle of a safe block. Moves with WASD relative to where we're already looking
     * (no turning), sneaking so it can never walk off an edge. True once we're there.
     */
    private fun centered(p: LocalPlayer): Boolean {
        if (! standCentre.value || ! p.onGround()) return true
        val lvl = p.level()
        val here = BlockPos.containing(p.position())
        var target = centreTarget
        if (target == null || BlockPos.containing(target).distManhattan(here) > 2) {
            val pick = (listOf(here) + listOf(here.north(), here.south(), here.east(), here.west(),
                here.north().east(), here.north().west(), here.south().east(), here.south().west()))
                .filter { safeStand(lvl, it) }
                .minByOrNull { Vec3.atBottomCenterOf(it).distanceToSqr(p.position()) }
                ?: return true // nowhere better nearby: stay
            target = Vec3.atBottomCenterOf(pick)
            centreTarget = target
        }
        val dx = target.x - p.x
        val dz = target.z - p.z
        if (dx * dx + dz * dz < 0.1 * 0.1 || ++centreTicks > 100) {
            releaseCentre()
            centreTicks = 0
            return true
        }
        val yr = Math.toRadians(p.yRot.toDouble())
        val f = dx * - kotlin.math.sin(yr) + dz * kotlin.math.cos(yr) // along where we look
        val r = dx * - kotlin.math.cos(yr) + dz * - kotlin.math.sin(yr) // to our right
        ownMoveTicks = 20
        mc.options.keyShift.isDown = true
        mc.options.keyUp.isDown = f > 0.05
        mc.options.keyDown.isDown = f < - 0.05
        mc.options.keyRight.isDown = r > 0.05
        mc.options.keyLeft.isDown = r < - 0.05
        return false
    }

    private fun releaseCentre() {
        mc.options.keyShift.isDown = false
        mc.options.keyUp.isDown = false
        mc.options.keyDown.isDown = false
        mc.options.keyRight.isDown = false
        mc.options.keyLeft.isDown = false
    }

    /**
     * True when a Wither Impact with this look can't drop us into lava: follows the 10-block teleport until
     * something blocks the body, then checks there's solid ground (not lava) under where we'd end up.
     */
    private fun blastSafe(p: LocalPlayer, yaw: Float, pitch: Float): Boolean {
        val lvl = p.level()
        val look = Vec3.directionFromRotation(pitch, yaw)
        val eyeH = p.eyeHeight.toDouble()
        fun solid(b: BlockPos) = ! lvl.getBlockState(b).getCollisionShape(lvl, b).isEmpty
        var end = p.position()
        var d = 0.25
        while (d <= 10.0) {
            val feet = p.eyePosition.add(look.scale(d)).subtract(0.0, eyeH, 0.0)
            val bp = BlockPos.containing(feet)
            if (solid(bp) || solid(bp.above())) break
            end = feet
            d += 0.25
        }
        val at = BlockPos.containing(end)
        if (lvl.getFluidState(at).`is`(FluidTags.LAVA) || lvl.getFluidState(at.above()).`is`(FluidTags.LAVA)) return false
        for (dy in 1 .. 4) {
            val b = at.below(dy - 1).below()
            if (lvl.getFluidState(b).`is`(FluidTags.LAVA)) return false
            if (solid(b)) return true
        }
        return false // nothing under us: we'd fall
    }

    // ------------------------------------------------------------------ aiming the cast

    /** The fluid surface height at (or just below) [pos]. */
    private fun surfaceY(pos: BlockPos): Double {
        for (dy in 2 downTo - 6) {
            val b = pos.offset(0, dy, 0)
            if (isFluid(b) && ! isFluid(b.above())) return b.y + 0.88
        }
        return pos.y.toDouble()
    }

    /**
     * Where a hook cast from here with this yaw / pitch comes down on a surface at [surface] (vanilla throw:
     * 0.6 blocks/tick along the look, 0.92 drag, 0.03 gravity). Null if it hits a block on the way.
     */
    private fun landing(p: LocalPlayer, yaw: Float, pitch: Float, surface: Double): Vec3? {
        val r = Math.PI / 180.0
        val f2 = kotlin.math.cos(- yaw * r - Math.PI)
        val f3 = kotlin.math.sin(- yaw * r - Math.PI)
        val f4 = - kotlin.math.cos(- pitch * r)
        val f5 = kotlin.math.sin(- pitch * r)
        var pos = Vec3(p.x - f3 * 0.3, p.eyeY, p.z - f2 * 0.3)
        var v = Vec3(- f3, (- (f5 / f4)).coerceIn(- 5.0, 5.0), - f2)
        v = v.scale(0.6 / v.length() + 0.5) // vanilla: 0.6 / length + 0.5 (the 0.5 has a tiny random spread)
        val level = p.level()
        repeat(160) {
            val next = pos.add(v)
            val hit = level.clip(net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
            if (hit.type != net.minecraft.world.phys.HitResult.Type.MISS) return null
            if (next.y <= surface && v.y < 0) return next
            pos = next
            v = Vec3(v.x * 0.92, v.y * 0.92 - 0.03, v.z * 0.92)
        }
        return null
    }

    /** Finds the yaw / pitch whose throw lands on [target]. */
    private fun solveAim(p: LocalPlayer, target: Vec3): Pair<Float, Float>? {
        val surface = surfaceY(BlockPos.containing(target))
        val yaw = Math.toDegrees(kotlin.math.atan2(target.z - p.z, target.x - p.x)).toFloat() - 90f
        val tries = ArrayList<Pair<Float, Double>>()
        var pitch = - 55f
        while (pitch <= 50f) {
            landing(p, yaw, pitch, surface)?.let { tries += pitch to horizontalDist(it, target) }
            pitch += 0.25f
        }
        val bestErr = tries.minOfOrNull { it.second } ?: return null
        // a short and a high throw can land on the same spot: take the flatter one, like people do
        val best = tries.filter { it.second <= bestErr + 0.25 }.maxByOrNull { it.first } ?: return null
        return yaw to best.first
    }

    private fun aimAt(p: LocalPlayer, hs: ArmorStand) {
        aimTarget = Vec3(hs.x, hs.y, hs.z)
        aimTries = 0
        val sol = solveAim(p, aimTarget!!)
        if (sol != null) {
            anchorYaw = sol.first
            anchorPitch = sol.second
        }
        else {
            val rot = MathUtils.calcYawPitch(hs.position())
            anchorYaw = rot.yaw
            anchorPitch = (rot.pitch - 8f).coerceIn(- 30f, 60f)
        }
        nextCastAim(p)
    }

    /** This cast's aim: the solved aim plus a small hand wobble. */
    private fun nextCastAim(@Suppress("UNUSED_PARAMETER") p: LocalPlayer) {
        val w = aimWobble.value.toFloat()
        wobYaw = (RANDOM.nextFloat() * 2 - 1) * w
        wobPitch = (RANDOM.nextFloat() * 2 - 1) * w * 0.6f
    }

    /** Is the hook inside the hotspot? If not, move the aim point by the miss and solve the throw again (8 tries). */
    private fun landedIn(p: LocalPlayer, hook: FishingHook, hs: ArmorStand): Boolean {
        val off = hook.position().subtract(hs.position())
        if (kotlin.math.sqrt(off.x * off.x + off.z * off.z) <= hotspotRadius.value || aimTries >= 8) return true
        aimTries++
        val base = aimTarget ?: Vec3(hs.x, hs.y, hs.z)
        aimTarget = Vec3(base.x - off.x * 0.85, base.y, base.z - off.z * 0.85)
        solveAim(p, aimTarget!!)?.let { (y, pi) -> anchorYaw = y; anchorPitch = pi }
        nextCastAim(p)
        returning = true
        status = "hook landed beside the hotspot, re-aiming ($aimTries)"
        return false
    }

    // ------------------------------------------------------------------ sea creatures

    /**
     * Works out which new mobs are ours. Looks around each of our reels (not around the player, so a hook far
     * below or above still counts) and gives a new mob 30 ticks to settle, because the server can spawn it a
     * little off and move it into place.
     */
    private fun claimOwn(p: LocalPlayer) {
        val lvl = p.level()
        // remember when every mob around us first showed up, all the time - so a creature someone else
        // fished up earlier is never mistaken for a new one just because we reeled in next to it
        val around = lvl.getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(40.0, 24.0, 40.0)) { it.isAlive && it.javaClass.simpleName !in NPC_CLASSES }
        for (e in around) if (e.id !in seenAt) { seenAt[e.id] = tickCount; seenPos[e.id] = e.position() }
        if (seenAt.size > 3000) { val live = around.map { it.id }.toSet(); seenAt.keys.retainAll(live); seenPos.keys.retainAll(live) }

        val window = (ownWindow.value * 20).toInt()
        while (reels.isNotEmpty() && tickCount - reels.first().tick > window + 5) reels.removeFirst()
        for (e in around) {
            if (e.id in mine) continue
            val first = seenAt[e.id] ?: continue
            val at = seenPos[e.id] ?: continue
            // ours only if it appeared AFTER one of our reels, within the window, right where our hook was
            if (reels.any { first >= it.tick && first - it.tick <= window && at.distanceTo(it.at) <= ownDistance.value }) {
                mine += e.id
                mineAlive += e.id
                firstSeen += e.id
                creaturesCaught++
            }
        }
    }

    /** The SkyBlock name tag that floats over a mob ("[Lv45] Sea Walker 1,200/1,200❤"), if there is one. */
    private fun tagOf(e: Entity): String? {
        val box = e.boundingBox.inflate(0.4, 2.5, 0.4)
        return e.level().getEntitiesOfClass(ArmorStand::class.java, box)
            .mapNotNull { it.customName?.string }
            .firstOrNull { "❤" in it }
    }

    /** "0❤" on its name tag: SkyBlock keeps a dying creature around for a moment. */
    private fun deadTag(e: Entity) = tagOf(e)?.let { Regex("(?:^|\\s)0/[\\d,.]+[kKmMbB]?❤").containsMatchIn(it.replace(Regex("§."), "")) || it.replace(Regex("§."), "").contains(" 0❤") } == true

    private fun creatures(p: LocalPlayer): List<LivingEntity> {
        val r = killRange.value
        val scan = maxOf(r, 24.0)
        val all = p.level().getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(scan, 8.0, scan)) { e ->
            e.isAlive && e.health > 0f && e.javaClass.simpleName !in NPC_CLASSES
        }
        if (firstSeen.size > 2000) { firstSeen.retainAll(all.map { it.id }.toSet()); mine.retainAll(firstSeen) }
        // anything actually hitting us counts too (self-defence)
        if (p.hurtTime > 0) all.filter { it.distanceTo(p) <= 3.5f && p.hasLineOfSight(it) }.forEach { mine += it.id; ignored -= it.id; inReach.remove(it.id) }
        return all.filter { e ->
            e.id !in ignored && e.distanceTo(p) <= r && (! onlyMine.value || e.id in mine) && ! deadTag(e)
        }
    }

    private fun hasHype() = weaponSlot.value !in 1 .. 9 && PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES } != null

    private fun fight(p: LocalPlayer, mobs: List<LivingEntity>) {
        val hypeSlot = if (weaponSlot.value in 1 .. 9) null else PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES }
        // no mana: the Hyperion is still a sword, swing it instead of standing there
        val hype = hypeSlot?.takeIf { ! blastBlocked && com.github.noamm9.utils.ManaGuard.canCast(manaCost.value) }
        if (hypeSlot != null && hype == null) hold(hypeSlot)
        val reach = if (hype != null) blastRange.value else 3.2
        // a creature that's in reach but never loses health (out of the blast, immune, already dead): leave it
        val inReachLimit = maxOf(giveUp.value * 20 * 2, 200)
        for (m in mobs) if (p.distanceTo(m) <= reach) {
            val (t, hp) = inReach[m.id] ?: (0 to m.health)
            val n = if (m.health < hp - 0.01f) 0 else t + 1
            inReach[m.id] = n to m.health
            if (n > inReachLimit && p.hurtTime == 0) { ignored += m.id; inReach.remove(m.id) }
        }
        if (inReach.size > 200) inReach.keys.retainAll(mobs.map { it.id }.toSet())
        for (m in mobs) {
            if (p.distanceTo(m) <= reach) waiting.remove(m.id)
            else if ((waiting.merge(m.id, 1, Int::plus) ?: 0) > giveUp.value * 20) {
                ignored += m.id
                waiting.remove(m.id)
            }
        }
        val close = mobs.filter { p.distanceTo(it) <= reach }
        if (close.isEmpty()) {
            val far = mobs.minByOrNull { it.distanceToSqr(p) } ?: return
            status = "waiting for ${far.name.string}"
            // keep an eye on it loosely: only turn when it's well off to the side, and not onto its exact middle
            val rot = MathUtils.calcYawPitch(com.github.noamm9.utils.Humanize.aimPoint(far.boundingBox.center, 0.6))
            if (abs(wrap(rot.yaw - p.yRot)) > 22f || abs(rot.pitch - p.xRot) > 18f) turn(p, rot.yaw, rot.pitch, 9f)
            return
        }
        if (! centered(p)) { status = "stepping to the middle of the block"; return }
        if (hype != null) {
            status = "Hyperion on ${close.size}"
            if (hypeBeat > 0) { hypeBeat--; return }
            when (hypeStage) {
                0 -> { // glance at the creature first (still holding the rod)
                    val m = close.minByOrNull { it.distanceToSqr(p) } ?: return
                    val rot = MathUtils.calcYawPitch(com.github.noamm9.utils.Humanize.aimPoint(m.boundingBox.center, 0.5))
                    if (abs(wrap(rot.yaw - p.yRot)) > 15f && ! turn(p, rot.yaw, rot.pitch, 10f)) return
                    hypeStage = 1
                    hypeBeat = delay(3, 9)
                }
                1 -> { // then look down so the blast covers everything around us
                    if (castDownPitch == 0f) castDownPitch = 78f + RANDOM.nextFloat() * 11f
                    if (! blastSafe(p, p.yRot, castDownPitch)) castDownPitch = 90f
                    if (! turn(p, p.yRot, castDownPitch, 3f)) return
                    val swapped = p.inventory.selectedSlot != hype
                    hold(hype) // the Hyperion comes out once we're looking down
                    hypeStage = 2
                    hypeBeat = if (swapped) delay(3, 8) else delay(2, 7) // a beat with it in hand before clicking
                }
                else -> { // cast; between casts keep looking down, each cast a little different
                    if (hitCooldown > 0) return
                    if (! blastSafe(p, p.yRot, p.xRot)) { // this look would teleport us off the edge: straight down instead
                        castDownPitch = 90f
                        if (abs(p.xRot - 90f) > 0.5f) { hypeStage = 1; return }
                        if (! blastSafe(p, p.yRot, 90f)) { // no safe blast from here: fight it with the sword instead
                            blastBlocked = true
                            hypeStage = 0
                            return
                        }
                    }
                    hold(hype)
                    ownMoveTicks = 20 // Wither Impact moves us: our doing, not the server's
                    use(p)
                    hitCooldown = delay(hitDelay.value, hitDelay.value * 2 + 4)
                    castDownPitch = (castDownPitch + (RANDOM.nextFloat() * 2 - 1) * 3f).coerceIn(76f, 90f)
                    if (abs(castDownPitch - p.xRot) > 1.5f) { hypeStage = 1; hypeBeat = delay(1, 4) }
                }
            }
            return
        }
        if (weaponSlot.value in 1 .. 9) hold(weaponSlot.value - 1)
        val target = close.minByOrNull { it.distanceToSqr(p) } ?: return
        status = if (hypeSlot != null && hype == null) "no mana, hitting ${target.name.string}" else "fighting ${target.name.string}"
        val rot = MathUtils.calcYawPitch(com.github.noamm9.utils.Humanize.aimPoint(target.boundingBox.center, 0.25))
        if (! turn(p, rot.yaw, rot.pitch, 4f) || hitCooldown > 0) return
        hitCooldown = hitDelay.value
        mc.gameMode?.attack(p, target)
        p.swing(InteractionHand.MAIN_HAND)
    }
}
//#endif
