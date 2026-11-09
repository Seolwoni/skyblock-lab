package dev.marceline.features.impl.dungeon

import dev.marceline.config.ConfigHolder
import dev.marceline.config.types.TextInputSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.DungeonEvent
import dev.marceline.features.Feature
import dev.marceline.utils.*
import dev.marceline.utils.dungeons.map.handlers.ScoreTracker
import dev.marceline.utils.location.LocationUtils
import dev.marceline.utils.render.Render2D.drawString
import dev.marceline.utils.render.RenderHelper.width
import net.minecraft.sounds.SoundEvents

object ScoreCalculator: Feature("Shows the score of the dungeon run.") {
    val forcePaul by ToggleSetting("Force Paul")
    private val hudElement by ToggleSetting("HUD Element")
    private val sendMimic by ToggleSetting("Send Mimic Message", true)
    private val sendPrince by ToggleSetting("Send Prince Message", true)
    private val sendBat by ToggleSetting("Send Bat Message", true)

    private val sendMsg270 by ToggleSetting("270 score message").section("270")
    private val msg270 by TextInputSetting("Message", "270 Score!").showIf { sendMsg270.value }
    private val title270 by ToggleSetting("270 score Title")
    private val titleMsg270 by TextInputSetting("Title Message", "&e270 Score!").showIf { title270.value }

    private val sendMsg300 by ToggleSetting("300 score message").section("300")
    private val msg300 by TextInputSetting("Message ", "300 Score!").showIf { sendMsg300.value }
    private val title300 by ToggleSetting("300 score Title")
    private val titleMsg300 by TextInputSetting("Title Message ", "&c300 Score!").showIf { title300.value }

    private data class Milestone(
        val score: Int,
        val sendMessage: ConfigHolder<Boolean>,
        val message: ConfigHolder<String>,
        val sendTitle: ConfigHolder<Boolean>,
        val title: ConfigHolder<String>
    )

    private val milestones by lazy {
        setOf(
            Milestone(270, sendMsg270, msg270, title270, titleMsg270),
            Milestone(300, sendMsg300, msg300, title300, titleMsg300)
        )
    }

    override fun init() {
        hudElement("ScoreCalculator", enabled = { hudElement.value }, shouldDraw = { LocationUtils.inDungeon }) { ctx, demoMode ->
            val text = if (demoMode) "&eScore: &a300"
            else "&eScore: " + ColorUtils.colorizeScore(ScoreTracker.score)

            ctx.drawString(text, 0, 0)
            return@hudElement text.width() to 9f
        }

        register<DungeonEvent.Score> { milestones.filter { it.score > event.oldScore && it.score <= event.score }.forEach(::triggerMilestone) }
        register<DungeonEvent.MimicKilled> { if (sendMimic.value) ChatUtils.sendPartyMessage("Mimic Killed") }
        register<DungeonEvent.PrinceKilled> { if (sendPrince.value) ChatUtils.sendPartyMessage("Prince Killed") }
        register<DungeonEvent.BatKilled> { if (sendBat.value) ChatUtils.sendPartyMessage("Bat Killed") }
    }

    private fun triggerMilestone(m: Milestone) {
        if (m.sendMessage.value) ChatUtils.sendPartyMessage(m.message.value)
        if (m.sendTitle.value) ChatUtils.showTitle(m.title.value)

        val timeStr = NumbersUtils.formatTime(ScoreTracker.secondsElapsed * 1000).ifEmpty { "0s" }
        val floorColor = if (LocationUtils.isMasterMode) "&c" else "&a"
        val floorName = LocationUtils.dungeonFloor ?: "?"

        ChatUtils.modMessage("&e${m.score}&a score reached in &6$timeStr &f|| $floorColor$floorName.")
        repeat(2) { SoundUtils.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.25f, 0f) }
    }
}