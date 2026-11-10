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
 * Auto Fishing's run state. AutoFishing implements [FishingVars] by delegating to [FishingVarsImpl],
 * so every part of the feature still reads and writes these as `AutoFishing.x`.
 */
interface FishingVars {
    val RANDOM: Random
    var timing: dev.marceline.utils.HumanTiming
    var hopThisStretch: Boolean
    var stretchLeft: Int
    var justCaughtSomething: Boolean
    val WITHER_BLADES: Set<String>
    val WHIPS: Map<Int, String>
    val NPC_CLASSES: Set<String>
    var running: Boolean
    var state: FishingState
    var wait: Int
    var timer: Int
    var inFluidTicks: Int
    var hitCooldown: Int
    var lastClickGap: Int
    var castYawSeries: Double
    var castPitchSeries: Double
    var reelTiming: dev.marceline.utils.HumanTiming
    var status: String
    var lastMobIds: Set<Int>
    val waiting: HashMap<Int, Int>
    val inReach: HashMap<Int, Pair<Int, Float>>
    var manaWait: Int
    var fightTicks: Int
    var blastBlocked: Boolean
    var centreTicks: Int
    var centreTarget: Vec3?
    val ignored: HashSet<Int>
    var groundTicks: Int
    var groundFails: Int
    var lastHookPos: Vec3?
    var anchorYaw: Float
    var anchorPitch: Float
    var offYaw: Float
    var offPitch: Float
    var fromYaw: Float
    var fromPitch: Float
    var toYaw: Float
    var toPitch: Float
    var moveTick: Int
    var moveLen: Int
    var nextMoveIn: Int
    var returning: Boolean
    var hotspot: Entity?
    var stand: Vec3?
    val badHotspots: HashSet<Int>
    var aimTries: Int
    var travelStuck: Int
    var travelGiveUps: Int
    var ownMoveTicks: Int
    var expectedSlot: Int
    var lastLookYaw: Float?
    var lastLookPitch: Float
    var lastTickPos: Vec3?
    var lastTickLevel: Any?
    var lastTickPlayer: Any?
    var path: List<BlockPos>
    var pathIndex: Int
    var pathGoal: Vec3?
    var lastSafe: Vec3?
    var lavaTicks: Int
    var guardTicks: Int
    var guardTeleported: Boolean
    var hopWait: Int
    var repath: Int
    var sellStep: Int
    var sellTimer: Int
    var sellGap: Int
    var sellTitle: String?
    val sellQueue: ArrayDeque<Int>
    var sellFails: Int
    var sold: Int
    var hop: AutoFishing.Hop?
    var hopPhase: Int
    var hopTimer: Int
    var hopFrom: Vec3
    var planIn: Int
    var radarStage: Int
    var radarTicks: Int
    var radarCooldown: Int
    var radarOrigin: Vec3?
    val radarPts: ArrayList<Vec3>
    val radarLines: ArrayList<Pair<Vec3, Vec3>>
    var radarGuess: Vec3?
    val remembered: HashMap<Long, Pair<Vec3, Long>>
    var scouting: Boolean
    var aimTarget: Vec3?
    var wobYaw: Float
    var wobPitch: Float
    var sessionStart: Long
    var kills: Int
    var creaturesCaught: Int
    val mineAlive: HashSet<Int>
    var lastLanding: Vec3?
    var nextBreakAt: Long
    var breakUntil: Long
    var glanceIn: Int
    val reels: ArrayDeque<AutoFishing.Reel>
    val firstSeen: HashSet<Int>
    val mine: HashSet<Int>
    val seenAt: HashMap<Int, Int>
    val seenPos: HashMap<Int, Vec3>
    val catches: ArrayDeque<Pair<Int, String>>
    val chase: HashMap<Int, Pair<Int, Double>>
    var claimsLeft: Int
    var catchMsgsWork: Boolean
    val journal: dev.marceline.utils.Journal
    var lastLoggedState: FishingState?
    var fightMobs: List<LivingEntity>
    var hypeFrom: Vec3?
    var heightWarned: Boolean
    var noManaLoggedAt: Int
    var healthWaiting: Boolean
    var serverSlotAt: Int
    var biteAt: Vec3?
    var tabStats: String
    var guiWasOpen: Boolean
    var guiLockUntil: Int
    var settledAt: Vec3?
    var lastParticleRecast: Int
    val dustAt: ArrayDeque<Int>
    val claimedAt: HashMap<Int, Long>
    val despawnWarned: HashSet<Int>
    val rareIds: HashSet<Int>
    var hotspotAt: Vec3?
    val castHp: HashMap<Int, Float>
    var immuneWait: Int
    val immuneWaited: HashSet<Int>
    val deaths: ArrayDeque<Pair<Int, Vec3>>
    var arrivedAt: Int
    var nearGoalTicks: Int
    var goldenSeenAt: Int
    var fishTarget: Vec3?
    var walkBest: Double
    var walkNoProgress: Int
    var walkReplans: Int
    var fightLeftAt: Int
    var noReachTicks: Int
    var aimFixes: Int
    var rebootAt: Long
    var rebootSpot: Vec3?
    var rebootHotspot: Boolean
    var rebootArea: String?
    var resumeStage: Int
    var resumeWait: Int
    var warpTries: Int
    var trackerAtStart: Map<String, Long>
    var breakMs: Long
    var hotspotsVisited: Int
    val rareLog: ArrayList<String>
    var statGround: Int
    var statRelink: Int
    var statSplash: Int
    var statDrift: Int
    var statLava: Int
    var statKnocks: Int
    var sellPass: Int
    var sellClicked: Int
    var sellWaitClick: Int
    var sellMenuId: Int
    val sellSnapshot: HashMap<Int, String>
    val sellRefused: HashSet<String>
    var soldThisRun: Int
    var statDeaths: Int
    var statStalls: Int
    val deaths10m: ArrayDeque<Int>
    var resumeWhy: String
    var stallSig: String
    var stallSince: Int
    var lastReport: List<String>
    val noticeAt: HashMap<Int, Int>
    var tickCount: Int
    var fightWait: Int
    var castDownPitch: Float
    var hypeStage: Int
    var hypeBeat: Int
    val fishIcon: net.minecraft.resources.Identifier
    var walkSteer: Boolean
    var walkGoalYaw: Float
    var walkGoalPitch: Float
    var walkPitch: Float
    var track: LivingEntity?
    var trackLoose: Boolean
    var trackSettle: Int
    var trackVelYaw: Float
    var trackVelPitch: Float
    var trackLastNs: Long
    var trackOff: Vec3
    var trackOffGoal: Vec3
    var trackOffNext: Long
    var trackMaxSpeed: Float
}

class FishingVarsImpl : FishingVars {

    override val RANDOM: Random = Random()
    override var timing: dev.marceline.utils.HumanTiming = dev.marceline.utils.HumanTiming()
    override var hopThisStretch: Boolean = true
    override var stretchLeft: Int = 0
    override var justCaughtSomething: Boolean = false
    override val WITHER_BLADES: Set<String> = dev.marceline.utils.SkyblockText.WITHER_BLADES
    override val WHIPS: Map<Int, String> = mapOf(1 to dev.marceline.utils.SkyblockText.SOUL_WHIP, 2 to dev.marceline.utils.SkyblockText.FLAMING_FLAY)
    override val NPC_CLASSES: Set<String> = setOf("Villager", "WanderingTrader")

    override var running: Boolean = false
    override var state: FishingState = FishingState.CAST
    override var wait: Int = 0
    override var timer: Int = 0
    override var inFluidTicks: Int = 0
    override var hitCooldown: Int = 0
    override var lastClickGap: Int = - 1
    override var castYawSeries: Double = 0.0
    override var castPitchSeries: Double = 0.0
    override var reelTiming: dev.marceline.utils.HumanTiming = dev.marceline.utils.HumanTiming()
    override var status: String = "idle"
    override var lastMobIds: Set<Int> = emptySet<Int>()
    override val waiting: HashMap<Int, Int> = HashMap<Int, Int>()
    override val inReach: HashMap<Int, Pair<Int, Float>> = HashMap<Int, Pair<Int, Float>>() // id -> ticks in reach without losing health, last health
    override var manaWait: Int = 0
    override var fightTicks: Int = 0
    override var blastBlocked: Boolean = false
    override var centreTicks: Int = 0
    override var centreTarget: Vec3? = null
    override val ignored: HashSet<Int> = HashSet<Int>()
    override var groundTicks: Int = 0
    override var groundFails: Int = 0
    override var lastHookPos: Vec3? = null

    // look: where we fish (anchor) + a small eased offset
    override var anchorYaw: Float = 0f
    override var anchorPitch: Float = 0f
    override var offYaw: Float = 0f
    override var offPitch: Float = 0f
    override var fromYaw: Float = 0f
    override var fromPitch: Float = 0f
    override var toYaw: Float = 0f
    override var toPitch: Float = 0f
    override var moveTick: Int = 0
    override var moveLen: Int = 0
    override var nextMoveIn: Int = 0
    override var returning: Boolean = false

    // hotspots / location
    override var hotspot: Entity? = null  // the floating "HOTSPOT" text (armor stand or text display)
    override var stand: Vec3? = null
    override val badHotspots: HashSet<Int> = HashSet<Int>()
    override var aimTries: Int = 0
    override var travelStuck: Int = 0
    override var travelGiveUps: Int = 0 // ways given up on in a row (3: stop)
    override var ownMoveTicks: Int = 0
    override var expectedSlot: Int = - 1
    override var lastLookYaw: Float? = null
    override var lastLookPitch: Float = 0f
    override var lastTickPos: Vec3? = null
    override var lastTickLevel: Any? = null
    override var lastTickPlayer: Any? = null

    // safe walking (own path, avoids lava / water)
    override var path: List<BlockPos> = emptyList()
    override var pathIndex: Int = 0
    override var pathGoal: Vec3? = null
    override var lastSafe: Vec3? = null
    override var lavaTicks: Int = 0
    override var guardTicks: Int = 0 // ticks of a knock towards lava being answered
    override var guardTeleported: Boolean = false // the teleport back was used for this knock
    override var hopWait: Int = 0
    override var repath: Int = 0

    // selling
    override var sellStep: Int = 0
    override var sellTimer: Int = 0
    override var sellGap: Int = 0
    override var sellTitle: String? = null
    override val sellQueue: ArrayDeque<Int> = ArrayDeque<Int>()
    override var sellFails: Int = 0
    override var sold: Int = 0
    override var hop: AutoFishing.Hop? = null
    override var hopPhase: Int = 0
    override var hopTimer: Int = 0
    override var hopFrom: Vec3 = Vec3.ZERO
    override var planIn: Int = 0
    override var radarStage: Int = 0
    override var radarTicks: Int = 0
    override var radarCooldown: Int = 0
    override var radarOrigin: Vec3? = null
    override val radarPts: ArrayList<Vec3> = ArrayList<Vec3>()
    override val radarLines: ArrayList<Pair<Vec3, Vec3>> = ArrayList<Pair<Vec3, Vec3>>() // where we read it, flat direction
    override var radarGuess: Vec3? = null
    override val remembered: HashMap<Long, Pair<Vec3, Long>> = HashMap<Long, Pair<Vec3, Long>>() // block key -> position, last seen
    override var scouting: Boolean = false

    // aiming the cast
    override var aimTarget: Vec3? = null
    override var wobYaw: Float = 0f
    override var wobPitch: Float = 0f

    // session numbers for the status card
    override var sessionStart: Long = 0L
    override var kills: Int = 0
    override var creaturesCaught: Int = 0
    override val mineAlive: HashSet<Int> = HashSet<Int>()
    override var lastLanding: Vec3? = null
    override var nextBreakAt: Long = 0L
    override var breakUntil: Long = 0L
    override var glanceIn: Int = 0
    override val reels: ArrayDeque<AutoFishing.Reel> = ArrayDeque<AutoFishing.Reel>()
    override val firstSeen: HashSet<Int> = HashSet<Int>()
    override val mine: HashSet<Int> = HashSet<Int>()
    override val seenAt: HashMap<Int, Int> = HashMap<Int, Int>()
    override val seenPos: HashMap<Int, Vec3> = HashMap<Int, Vec3>()
    override val catches: ArrayDeque<Pair<Int, String>> = ArrayDeque<Pair<Int, String>>() // tick, lower-case catch message
    override val chase: HashMap<Int, Pair<Int, Double>> = HashMap<Int, Pair<Int, Double>>() // id -> ticks coming at us, last distance
    override var claimsLeft: Int = 0
    override var catchMsgsWork: Boolean = false
    override val journal: dev.marceline.utils.Journal = dev.marceline.utils.Journal("fishing")
    override var lastLoggedState: FishingState? = null
    override var serverSlotAt: Int = - 1000 // tick the server last set the held slot
    override var healthWaiting: Boolean = false // below Low Health: no casts until Resume At
    override var noManaLoggedAt: Int = - 1000
    override var heightWarned: Boolean = false // told once per session that ours are below the Hyperion's reach
    override var hypeFrom: Vec3? = null // where the last Hyperion cast was fired from (journal: where it took us)
    override var fightMobs: List<LivingEntity> = emptyList() // sea creatures the shared check picked this tick (FIGHT handler)
    override var biteAt: Vec3? = null               // where the hook was at the bite (it can vanish before we reel)
    override var tabStats: String = ""
    override var guiWasOpen: Boolean = false
    override var guiLockUntil: Int = 0           // no reacting to a bite right after closing a menu
    override var settledAt: Vec3? = null    // where the hook came to rest (drift check)
    override var lastParticleRecast: Int = - 1000
    override val dustAt: ArrayDeque<Int> = ArrayDeque<Int>()   // ticks of dust particles seen right at the hook                      // "SCC 21% · DHC 8%" from the tab list
    override val claimedAt: HashMap<Int, Long> = HashMap<Int, Long>()   // id -> when it became ours (despawn warning)
    override val despawnWarned: HashSet<Int> = HashSet<Int>()
    override val rareIds: HashSet<Int> = HashSet<Int>()
    override var hotspotAt: Vec3? = null            // where the hotspot was when we picked it (it can move)
    override val castHp: HashMap<Int, Float> = HashMap<Int, Float>()
    override var immuneWait: Int = 0
    override val immuneWaited: HashSet<Int> = HashSet<Int>()
    override val deaths: ArrayDeque<Pair<Int, Vec3>> = ArrayDeque<Pair<Int, Vec3>>()   // tick, where one of ours died (split check)
    override var arrivedAt: Int = - 1000
    override var nearGoalTicks: Int = 0
    override var goldenSeenAt: Int = - 100000
    override var fishTarget: Vec3? = null
    override var walkBest: Double = Double.MAX_VALUE
    override var walkNoProgress: Int = 0
    override var walkReplans: Int = 0
    override var fightLeftAt: Int = - 1000
    override var noReachTicks: Int = 0
    override var aimFixes: Int = 0           // the water / lava point we walked to (no hotspot)
    override var rebootAt: Long = 0L
    override var rebootSpot: Vec3? = null
    override var rebootHotspot: Boolean = false
    override var rebootArea: String? = null
    override var resumeStage: Int = 0
    override var resumeWait: Int = 0
    override var warpTries: Int = 0
    // session report
    override var trackerAtStart: Map<String, Long> = emptyMap()
    override var breakMs: Long = 0L
    override var hotspotsVisited: Int = 0
    override val rareLog: ArrayList<String> = ArrayList<String>()
    override var statGround: Int = 0
    override var statRelink: Int = 0
    override var statSplash: Int = 0
    override var statDrift: Int = 0
    override var statLava: Int = 0
    override var statKnocks: Int = 0
    override var sellPass: Int = 0 // passes over the sell menu this time (rescans for what was missed)
    override var sellClicked: Int = - 1 // the last slot clicked, waiting for it to go
    override var sellWaitClick: Int = 0
    override var sellMenuId: Int = - 1 // the sell menu's window id (a new window means the slots moved)
    override var soldThisRun: Int = 0 // stacks sold in this visit to the sell menu
    override val sellRefused: HashSet<String> = HashSet() // items the sell menu wouldn't take: left alone this session
    override val sellSnapshot: HashMap<Int, String> = HashMap() // inventory slot -> what was there to sell, taken before the menu opened
    override var statDeaths: Int = 0
    override var statStalls: Int = 0 // times the stall watchdog started over
    override val deaths10m: ArrayDeque<Int> = ArrayDeque() // ticks of deaths in the last 10 minutes
    override var resumeWhy: String = "after the reboot"
    override var stallSig: String = "" // what the watchdog last saw (state, hook, counts, block)
    override var stallSince: Int = 0
    override var lastReport: List<String> = emptyList()     // creature health at our last blast (immunity check)

    // noticing sea creatures
    override val noticeAt: HashMap<Int, Int> = HashMap<Int, Int>()
    override var tickCount: Int = 0
    override var fightWait: Int = 0
    override var castDownPitch: Float = 0f
    override var hypeStage: Int = 0
    override var hypeBeat: Int = 0

    override val fishIcon: net.minecraft.resources.Identifier = net.minecraft.resources.Identifier.fromNamespaceAndPath(dev.marceline.Marceline.MOD_ID, "textures/gui/icons/fishing.png")

    // ------------------------------------------------------------------ walking state (AutoFishingTravel.kt)

    override var walkSteer: Boolean = false
    override var walkGoalYaw: Float = 0f
    override var walkGoalPitch: Float = 0f
    override var walkPitch: Float = 0f

    // ------------------------------------------------------------------ tracking state (AutoFishingCombat.kt)

    override var track: LivingEntity? = null
    override var trackLoose: Boolean = false
    override var trackSettle: Int = 0
    override var trackVelYaw: Float = 0f
    override var trackVelPitch: Float = 0f
    override var trackLastNs: Long = 0L
    override var trackOff: Vec3 = Vec3.ZERO       // where on the body we aim; drifts slowly, not a new point every tick
    override var trackOffGoal: Vec3 = Vec3.ZERO
    override var trackOffNext: Long = 0L
    override var trackMaxSpeed: Float = 300f
}

//#endif
