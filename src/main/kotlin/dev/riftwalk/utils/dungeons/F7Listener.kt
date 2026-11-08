package dev.riftwalk.utils.dungeons

import dev.riftwalk.Riftwalk.mc
import dev.riftwalk.event.EventBus
import dev.riftwalk.event.impl.*
import dev.riftwalk.init.types.ISelfInit
import dev.riftwalk.utils.ThreadUtils
import dev.riftwalk.utils.catch
import dev.riftwalk.utils.dungeons.enums.TerminalSection
import dev.riftwalk.utils.location.LocationUtils
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

object F7Listener: ISelfInit {
    private var necronDead = false
    private var necronStart = false

    var section = TerminalSection.UNKNOWN
        private set

    override fun init() {
        EventBus.register<ChatMessageEvent> {
            if (LocationUtils.dungeonFloorNumber != 7) return@register
            if (! LocationUtils.inBoss) return@register
            when (event.unformattedText) {
                "[BOSS] Necron: ARGH!" -> necronStart = true
                "[BOSS] Goldor: Who dares trespass into my domain?" -> section = TerminalSection.S1.start()
                "The Core entrance is opening!" -> section = TerminalSection.GOLDOR
            }

            if (section.ordinal in 1 .. 4) section = section.process(event.unformattedText)
        }

        // thx blade for finding out the right sound
        EventBus.register<MainThreadPacketReceivedEvent.Pre> {
            if (! necronStart) return@register
            if (necronDead) return@register
            val packet = event.packet as? ClientboundSoundPacket ?: return@register
            if (packet.sound.value() != SoundEvents.WITHER_HURT) return@register
            if (packet.volume != 15f) return@register
            if (packet.pitch != 1f) return@register

            EventBus.post(DungeonEvent.NecronDeathEvent)
            necronDead = true
        }

        EventBus.register<WorldChangeEvent> {
            necronStart = false
            necronDead = false
            TerminalSection.resetAll()
            section = TerminalSection.UNKNOWN
        }

        EventBus.register<MessageSentEvent> {
            if (! event.isCommand) return@register
            if (mc.connection?.serverData?.ip?.contains("p3sim", ignoreCase = true) != true) return@register
            val cmd = event.message.lowercase().split(" ")
            if (cmd.size < 2) return@register
            if (cmd[0] != "start") return@register
            val sec = catch { TerminalSection.valueOf(cmd[1].uppercase()) } ?: return@register
            ThreadUtils.setTimeout(3000) {
                section = sec.start()
            }
        }
    }
}