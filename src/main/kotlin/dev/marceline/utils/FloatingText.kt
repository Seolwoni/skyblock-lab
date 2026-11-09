package dev.marceline.utils

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import java.lang.reflect.Method

/**
 * Floating text in the world, whichever way the server shows it: an armor stand's name (older servers) or a
 * text display entity (newer Hypixel, e.g. the fishing "!!!" and countdown over the bobber).
 */
object FloatingText {
    private var textGetter: Method? = null
    private var looked = false

    /** The text a text display shows (read by type, so it works whatever the method is called at runtime). */
    fun of(e: Entity): String? {
        e.customName?.string?.let { return it }
        if (e !is Display.TextDisplay) return null
        if (! looked) {
            looked = true
            textGetter = Display.TextDisplay::class.java.declaredMethods.firstOrNull {
                it.parameterCount == 0 && Component::class.java.isAssignableFrom(it.returnType)
            }?.apply { isAccessible = true }
        }
        return runCatching { (textGetter?.invoke(e) as? Component)?.string }.getOrNull()
    }

    /** Every floating text in [box], with the entity showing it. */
    fun near(level: net.minecraft.world.level.Level, box: AABB): List<Pair<Entity, String>> =
        level.getEntities(null as Entity?, box) { it is net.minecraft.world.entity.decoration.ArmorStand || it is Display.TextDisplay }
            .mapNotNull { e -> of(e)?.let { e to it } }

    /** True when any floating text in [box] contains [needle]. */
    fun any(level: net.minecraft.world.level.Level, box: AABB, needle: String): Boolean =
        level.getEntities(null as Entity?, box) { it is net.minecraft.world.entity.decoration.ArmorStand || it is Display.TextDisplay }
            .any { of(it)?.contains(needle) == true }
}
