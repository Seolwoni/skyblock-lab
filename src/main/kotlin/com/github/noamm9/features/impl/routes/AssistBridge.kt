package com.github.noamm9.features.impl.routes

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos

/**
 * Optional link to seolwoni's Assist Core and Assist Path (both must be installed to be used).
 * Every call into them is behind a "loaded" check, so SkyBlock Lab runs the same without them.
 * Their classes are only referenced inside the functions (never as field types) so nothing loads them early.
 */
object AssistBridge {
    const val MOD = "skyblocklab"
    const val RUN = 0
    const val DONE = 1
    const val FAIL = 2

    val coreLoaded by lazy { FabricLoader.getInstance().isModLoaded("assistcore") }
    val pathLoaded by lazy { coreLoaded && FabricLoader.getInstance().isModLoaded("assistpath") }

    private var walker: Any? = null
    private var walkerGoal: BlockPos? = null

    /** Assist Core's H key hides every HUD box; SkyBlock Lab HUDs follow it. */
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
     * A new goal starts a new plan. Movement runs at Assist's fastest pace with the human-like delays off.
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
            val oldPace = com.seolwoni.assistcore.util.Human.pace
            val oldHuman = com.seolwoni.assistcore.util.Human.humanLike
            com.seolwoni.assistcore.util.Human.pace = 2
            com.seolwoni.assistcore.util.Human.humanLike = false
            val result = try {
                (walker as com.seolwoni.assistpath.walk.PathWalker).tick(mc, p)
            } finally {
                com.seolwoni.assistcore.util.Human.pace = oldPace
                com.seolwoni.assistcore.util.Human.humanLike = oldHuman
            }
            if (result != RUN) reset()
            result
        }.getOrElse { reset(); FAIL }
    }

    fun failReason(): String? = runCatching { (walker as? com.seolwoni.assistpath.walk.PathWalker)?.failReason() }.getOrNull()

    fun reset() {
        walker = null
        walkerGoal = null
    }
}
