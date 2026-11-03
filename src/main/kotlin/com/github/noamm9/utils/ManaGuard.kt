package com.github.noamm9.utils

import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.ActionBarMessageEvent
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.init.types.ISelfInit

/**
 * Knows whether there's mana for an ability: reads the action bar's mana (ActionBarParser), and remembers
 * a "NOT ENOUGH MANA" / "You do not have enough mana" for a few seconds in case the action bar is hidden.
 */
object ManaGuard: ISelfInit {
    private var lastOut = 0L

    override fun init() {
        EventBus.register<ActionBarMessageEvent> {
            if (event.component.string.contains("NOT ENOUGH MANA", ignoreCase = true)) lastOut = System.currentTimeMillis()
        }
        EventBus.register<ChatMessageEvent> {
            if (event.unformattedText.contains("do not have enough mana", ignoreCase = true)) lastOut = System.currentTimeMillis()
        }
    }

    /** Current mana, or null when the action bar hasn't shown it. */
    val mana: Int? get() = ActionBarParser.currentMana.takeIf { ActionBarParser.maxMana > 0 }

    /** Mana as 0..1 of the maximum, or null when unknown. */
    val fraction: Float? get() = if (ActionBarParser.maxMana > 0) ActionBarParser.currentMana / ActionBarParser.maxMana.toFloat() else null

    /** True when an ability costing [cost] can be used right now. */
    fun canCast(cost: Int): Boolean {
        if (System.currentTimeMillis() - lastOut < 2500) return false
        val m = mana ?: return true // unknown: try, the "not enough mana" message will tell us
        return m >= cost
    }
}
