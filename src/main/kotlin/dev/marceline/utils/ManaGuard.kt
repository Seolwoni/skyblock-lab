package dev.marceline.utils

import dev.marceline.event.EventBus
import dev.marceline.event.impl.ActionBarMessageEvent
import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.init.types.ISelfInit

/**
 * Knows whether there's mana for an ability: reads the action bar's mana (ActionBarParser), and remembers
 * a "NOT ENOUGH MANA" / "You do not have enough mana" for a few seconds in case the action bar is hidden.
 */
object ManaGuard: ISelfInit {
    private var lastOut = 0L

    override fun init() {
        EventBus.register<ActionBarMessageEvent> {
            if (event.component.string.contains(SkyblockText.NOT_ENOUGH_MANA_BAR, ignoreCase = true)) lastOut = System.currentTimeMillis()
        }
        EventBus.register<ChatMessageEvent> {
            if (event.unformattedText.contains(SkyblockText.NOT_ENOUGH_MANA_CHAT, ignoreCase = true)) lastOut = System.currentTimeMillis()
        }
    }

    /** True when the action bar showed the mana in the last 3 s; an old reading is not trusted. */
    private val fresh get() = ActionBarParser.maxMana > 0 && System.currentTimeMillis() - ActionBarParser.manaSeenAt < 3000

    /** Current mana, or null when the action bar hasn't shown it lately. */
    val mana: Int? get() = ActionBarParser.currentMana.takeIf { fresh }

    /** Maximum mana, or null when the action bar hasn't shown it lately. */
    val max: Int? get() = ActionBarParser.maxMana.takeIf { fresh }

    /** Mana as 0..1 of the maximum, or null when unknown. */
    val fraction: Float? get() = if (fresh) ActionBarParser.currentMana / ActionBarParser.maxMana.toFloat() else null

    /** True when an ability costing [cost] can be used right now. */
    fun canCast(cost: Int): Boolean {
        if (System.currentTimeMillis() - lastOut < 2500) return false
        val m = mana ?: return true // unknown: try, the "not enough mana" message will tell us
        return m >= cost
    }
}
