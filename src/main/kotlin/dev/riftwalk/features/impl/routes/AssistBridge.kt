package dev.riftwalk.features.impl.routes

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos

/**
 * Optional link to seolwoni's Assist Core and Assist Path (both must be installed to be used).
 * Every call into them is behind a "loaded" check, so Riftwalk runs the same without them.
 * Their classes are only referenced inside the functions (never as field types) so nothing loads them early.
 */
object AssistBridge {
    const val MOD = "riftwalk"
    const val RUN = 0
    const val DONE = 1
    const val FAIL = 2

    val coreLoaded by lazy { FabricLoader.getInstance().isModLoaded("assistcore") }
    val pathLoaded by lazy { coreLoaded && FabricLoader.getInstance().isModLoaded("assistpath") }

    private var walker: Any? = null
    private var walkerGoal: BlockPos? = null

    /** Assist Core's H key hides every HUD box; Riftwalk HUDs follow it. */
    fun hudHidden(): Boolean = coreLoaded && runCatching { com.seolwoni.assistcore.hud.AssistHud.hidden() }.getOrDefault(false)

    fun logStart(what: String) {
        if (coreLoaded) runCatching { com.seolwoni.assistcore.log.UsageLog.start(MOD, what) }
    }

    fun logEvent(text: String) {
        if (coreLoaded) runCatching { com.seolwoni.assistcore.log.UsageLog.event(MOD, text) }
    }

    fun logEnd(reason: String, summary: String) {
        if (coreLoaded) runCatching { com.seolwoni.assistcore.log.UsageLog.end(MOD, reason, summary) }
    }

    val walking get() = walker != null

    /**
     * Walks to [goal] with Assist Path (A*, no breaking or placing). Call every tick; returns RUN, DONE or FAIL.
     * A new goal starts a new plan. Walking uses Assist Core's smooth movement with the player's Assist settings.
     */
    fun walkTo(mc: Minecraft, p: LocalPlayer, goal: BlockPos, range: Int = 1): Int {
        if (! pathLoaded) return FAIL
        return runCatching {
            if (walker == null || walkerGoal != goal) {
                walker = com.seolwoni.assistpath.walk.PathWalker(
                    com.seolwoni.assistpath.goals.GoalNear(goal, range),
                    com.seolwoni.assistpath.PathOptions.WALK, 3
                )
                walkerGoal = goal
            }
            // Assist's own pace and human-like movement are kept as the player set them.
            val result = (walker as com.seolwoni.assistpath.walk.PathWalker).tick(mc, p)
            if (result != RUN) reset()
            result
        }.getOrElse { reset(); FAIL }
    }

    /** Assist Core's eased head turn. True once within [tolerance] degrees. */
    fun turn(p: LocalPlayer, yaw: Float, pitch: Float, tolerance: Float): Boolean =
        runCatching { com.seolwoni.assistcore.util.Body.turnTo(p, yaw, pitch, tolerance) }.getOrDefault(true)

    fun failReason(): String? = runCatching { (walker as? com.seolwoni.assistpath.walk.PathWalker)?.failReason() }.getOrNull()

    fun reset() {
        walker = null
        walkerGoal = null
    }
}
