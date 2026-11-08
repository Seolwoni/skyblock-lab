package dev.riftwalk.features.impl.general

import dev.riftwalk.Riftwalk
import dev.riftwalk.commands.CommandBuilder
import dev.riftwalk.commands.NaCommand
import dev.riftwalk.config.PogObject
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.event.impl.*
import dev.riftwalk.features.Feature
import dev.riftwalk.init.DataDownloader
import dev.riftwalk.init.types.ICommandProvider
import dev.riftwalk.interfaces.IChatComponent
import dev.riftwalk.ui.notification.NotificationManager
import dev.riftwalk.utils.*
import dev.riftwalk.utils.ChatUtils.removeFormatting
import dev.riftwalk.utils.Utils.send
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.*
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket

object ChatFeatures: Feature(jsonName = "Chat", description = "Useful tweaks for the chat such as Ctrl + Click to copy messages."), ICommandProvider {
    private val ctrlClickToCopy by ToggleSetting("Ctrl Click to Copy", true).withDescription("Ctrl + Left Click a message to copy it to your clipboard.")
    private val removeUselessMessages by ToggleSetting("Remove useless messages", true).withDescription("Removes a lot of useless messages from the chat.")
    private val chatEmoji by ToggleSetting("Chat Emoji", true).withDescription("Lets you use [MVP++] emojis in chat")

    //#if CHEAT
    private val autoDialogue by ToggleSetting("Auto dialogue").withDescription("Automatically continues dialogues with NPCs.")
    //#endif

    private val uselessMessages by lazy { DataDownloader.loadJson<List<String>>("uselessMessages.json").map(::Regex) }
    private val customHiders = PogObject("customHiders", mutableListOf<Regex>())

    private val explosiveShotRegex = Regex("^Your Explosive Shot hit (\\d+) (enemy|enemies) for ([\\d,.]+) damage\\.$") // https://regex101.com/r/xsBn8E/1
    private var lastMessageBlank = false

    override fun CommandBuilder.command() {
        setName(*NaCommand.names)
        literal("chathider") {
            description("Manages the custom chat hider regexes")
            requires("Enable the $name Feature to use /rw chathider.") { enabled }
            runs { ChatUtils.modMessage("&bUsage: /rw chathider <add|remove|list>") }

            literal("add") {
                argument("regex", StringArgumentType.greedyString()) {
                    runs {
                        val str = StringArgumentType.getString(it, "regex")
                        val list = customHiders.get().map(Regex::pattern)
                        if (str in list) return@runs ChatUtils.modMessage("&cRegex is Already in the list!")
                        val regex = catch { Regex(str) } ?: return@runs ChatUtils.modMessage("&cInvalid Regex syntax!")
                        customHiders.get().add(regex)
                        customHiders.save()
                        ChatUtils.modMessage("&aSaved new Regex.")
                    }
                }
            }

            literal("remove") {
                argument("regex", StringArgumentType.greedyString()) {
                    suggests { customHiders.get().map(Regex::pattern) }
                    runs {
                        val str = StringArgumentType.getString(it, "regex")
                        val removed = customHiders.get().removeIf { it.pattern == str }
                        ChatUtils.modMessage(if (removed) "&aRemoved Regex." else "&cNo matching regex found.")
                        customHiders.save()
                    }
                }
            }

            literal("list") {
                runs {
                    ChatUtils.modMessage("customHiders:")
                    ChatUtils.chat(customHiders.get().joinToString("\n") { it.pattern })
                }
            }
        }
    }

    override fun init() {
        register<MouseClickEvent> {
            if (! ctrlClickToCopy.value) return@register
            if (mc.gui.screen() !is ChatScreen) return@register
            if (event.button != InputConstants.MOUSE_BUTTON_LEFT) return@register
            if (event.action != InputConstants.PRESS) return@register
            if (! mc.hasControlDown()) return@register
            val message = getHoveredMsg().takeUnless(String::isBlank) ?: return@register

            NotificationManager.push("Message copied to clipboard", message)
            mc.keyboardHandler.clipboard = message
            event.isCanceled = true
        }

        register<ChatMessageEvent> {
            if (! removeUselessMessages.value) return@register
            val (sHits, _, fDamage) = explosiveShotRegex.find(event.unformattedText)?.destructured ?: return@register
            val hits = sHits.toIntOrNull() ?: return@register
            val totalDamage = fDamage.replace(",", "").toDoubleOrNull() ?: return@register
            val damagePerEntity = if (hits > 0) totalDamage / hits else totalDamage
            ChatUtils.modMessage("&aExplosive shot did &e${NumbersUtils.format(damagePerEntity.toLong())}&a damage per enemy.")
        }

        register<MessageSentEvent> {
            if (! chatEmoji.value) return@register
            event.message = escapedKeys.replace(event.message) { emotes[it.value] ?: it.value }
        }

        register<PrintMessageEvent> {
            if (! removeUselessMessages.value) return@register
            val msg = event.unformattedText

            if (msg.isBlank()) {
                if (lastMessageBlank) event.cancel()
                else ::lastMessageBlank.set(true)
                return@register
            }

            if (uselessMessages.any { it.matches(msg) } || customHiders.get().any { it.matches(msg) }) return@register event.cancel()

            lastMessageBlank = false
        }

        //#if CHEAT
        // https://github.com/jcnlk/quoi/blob/6e74cc3536aa1db91fe4a134254668a96c2ea072/src/main/kotlin/quoi/module/impl/general/chat/impl/AutoDialogue.kt#L4
        register<ChatMessageEvent> {
            if (! autoDialogue.value) return@register
            if ("[BARBARIANS] [MAGES]" in event.unformattedText) return@register
            if (! event.unformattedText.startsWith("Select an option: ")) return@register

            fun firstOption(c: Component): ClickEvent? = (c.style.clickEvent as? ClickEvent.RunCommand
                ?: c.style.clickEvent as? ClickEvent.Custom)
                ?: c.siblings.firstNotNullOfOrNull { firstOption(it) }

            when (val option = firstOption(event.component)) {
                is ClickEvent.RunCommand -> ChatUtils.sendCommand(option.command)
                is ClickEvent.Custom -> ServerboundCustomClickActionPacket(option.id, option.payload).send()
                else -> return@register
            }
        }
        //#endif
    }

    private fun getHoveredMsg(): String {
        val chatHud = (mc.gui.hud.chat as? IChatComponent) ?: return ""
        val i = chatHud.getLineIndex().takeUnless { it < 0 || it >= chatHud.visibleMessages.size } ?: return ""

        val lines = ArrayList<GuiMessage.Line>()
        val builder = StringBuilder()

        for (j in i.toInt() + 1 until chatHud.visibleMessages.size) {
            val line = chatHud.visibleMessages[j]
            if (line.endOfEntry()) break
            lines.add(0, line)
        }

        for (j in i.toInt() downTo 0) {
            val line = chatHud.visibleMessages[j]
            lines.add(line)
            if (line.endOfEntry()) break
        }

        for (line in lines) {
            var lastStyle: Style? = null
            line.content().accept { _, style, codePoint ->
                if (style != lastStyle) {
                    style.color?.let { textColor ->
                        ChatFormatting.entries.firstOrNull { TextColor.fromLegacyFormat(it)?.value == textColor.value }?.let {
                            builder.append(it)
                        }
                    }

                    if (style.isBold) builder.append(ChatFormatting.BOLD)
                    if (style.isItalic) builder.append(ChatFormatting.ITALIC)
                    if (style.isUnderlined) builder.append(ChatFormatting.UNDERLINE)
                    if (style.isStrikethrough) builder.append(ChatFormatting.STRIKETHROUGH)
                    if (style.isObfuscated) builder.append(ChatFormatting.OBFUSCATED)
                    lastStyle = style
                }
                builder.appendCodePoint(codePoint)
                true
            }
        }

        val text = builder.toString()
        return if ("chat" in Riftwalk.debugFlags) text else text.removeFormatting()
    }

    private val escapedKeys by lazy { emotes.keys.joinToString("|", transform = Regex::escape).toRegex() }
    private val emotes = mapOf(
        "<3" to "❤",
        "h/" to "ヽ(^◇^*)/",
        "o/" to "( ﾟ◡ﾟ)/",
        ":star:" to "✮",
        ":yes:" to "✔",
        ":no:" to "✖",
        ":java:" to "☕",
        ":arrow:" to "➜",
        ":shrug:" to "¯\\_(ツ)_/¯",
        ":tableflip:" to "(╯°□°）╯︵ ┻━┻",
        ":totem:" to "☉_☉",
        ":typing:" to "✎...",
        ":maths:" to "√(π+x)=L",
        ":snail:" to "@'-'",
        ":thinking:" to "(0.o?)",
        ":gimme:" to "༼つ◕_◕༽つ",
        ":wizard:" to "('-')⊃━☆ﾟ.*･｡ﾟ",
        ":pvp:" to "⚔",
        ":peace:" to "✌",
        ":puffer:" to "<('O')>",
        ":yey:" to "ヽ (◕◡◕) ﾉ",
        ":cat:" to "= ＾● ⋏ ●＾ =",
        ":dab:" to "<o/",
        ":dj:" to "ヽ(⌐■_■)ノ♬",
        ":snow:" to "☃",
        ":sloth:" to "(・⊝・)",
        ":cute:" to "(✿◠‿◠)",
        ":dog:" to "(ᵔᴥᵔ)",
        ":sob:" to ".ᐟ(つ╥﹏╥)つ",
        ":joy:" to "৻(≧ᗜ≦৻)",
        ":skull:" to "☠"
    )
}