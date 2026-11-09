package dev.marceline.features

import dev.marceline.config.ConfigHolder
import dev.marceline.config.SettingProvider
import dev.marceline.event.*
import dev.marceline.event.priority.EventPriority
import dev.marceline.init.RemoteFeatures
import dev.marceline.ui.clickgui.enums.CategoryType
import dev.marceline.ui.hud.HudElement
import dev.marceline.ui.hud.HudProvider
import dev.marceline.ui.notification.NotificationManager
import dev.marceline.utils.catch
import dev.marceline.utils.spaceCaps
import net.minecraft.client.gui.GuiGraphicsExtractor

open class Feature(
    description: String? = null,
    name: String? = null,
    toggled: Boolean = false,
    jsonName: String? = null,
): Shortcuts, SettingProvider, HudProvider {
    val name = name ?: this::class.simpleName.toString().spaceCaps()
    val jsonName = jsonName ?: this.name
    private val givenDescription = description
    /** The feature's own description, or a written fallback when it had none (see [FeatureDescriptions]). */
    val description: String? by lazy { FeatureDescriptions.fallback(this, this.name, givenDescription) }
    @JvmField var enabled = toggled
    open val category = initCategory()
    /** Left alone by the master switch ("All Off"): pure looks with no automation, like the menu screens. */
    open val keepOnMasterOff: Boolean get() = false

    private val remotelyDisabled get() = RemoteFeatures.isDisabled(this::class.java.simpleName)

    override val configSettings = mutableSetOf<ConfigHolder<*>>()
    val listeners = mutableSetOf<EventListener<*>>()
    override val hudElements = mutableSetOf<HudElement>()

    fun initialize() {
        init()

        if (remotelyDisabled) {
            enabled = false
            return
        }

        if (enabled) onEnable() else onDisable()
    }

    open fun init() = Unit
    open fun onEnable() = listeners.forEach(EventListener<*>::register)
    open fun onDisable() = listeners.forEach(EventListener<*>::unregister)

    open fun toggle() {
        if (remotelyDisabled) return NotificationManager.push("Config GUI", "&b$name&f is temporarily disabled.")

        enabled = ! enabled
        if (enabled) onEnable() else onDisable()
    }

    protected inline fun <reified T: Event> register(
        priority: EventPriority = EventPriority.NORMAL,
        receiveCancelled: Boolean = false,
        noinline block: EventContext<T>.() -> Unit
    ): EventListener<T> {
        val listener = EventBus.listener<T>(priority, receiveCancelled, block)
        listeners.add(listener)
        return listener
    }

    fun hudElement(
        name: String = this@Feature.jsonName,
        enabled: () -> Boolean = { true },
        shouldDraw: () -> Boolean = { true },
        centered: () -> Boolean = { false },
        render: (GuiGraphicsExtractor, Boolean) -> Pair<Number, Number>
    ) = object: HudElement() {
        override val name = name
        override val centered = centered
        override val toggle get() = this@Feature.enabled && enabled.invoke()
        override val shouldDraw get() = shouldDraw.invoke()
        override fun draw(ctx: GuiGraphicsExtractor, example: Boolean) = render(ctx, example)
    }.also(hudElements::add)

    /** Sub-heading inside the tab ("Core", "Floor 7", "Routes" in Dungeons); blank where a tab has one group. */
    val group: String get() = groupOf(packagePart())

    private fun packagePart(): String {
        val parts = this::class.java.`package` !!.name.split(".")
        return parts[parts.indexOf("impl") + 1].lowercase()
    }

    private fun initCategory(): CategoryType {
        val name = when (val p = packagePart()) { "floor7", "routes" -> "dungeon"; else -> p }.uppercase()
        return catch { CategoryType.valueOf(name) } ?: error("Category does not exist: $name")
    }

    companion object {
        /** Package name -> the heading it sits under in its tab. */
        @JvmStatic fun groupOf(pkg: String) = when (pkg) {
            "dungeon" -> "Core"
            "floor7" -> "Floor 7"
            "routes" -> "Routes"
            else -> ""
        }
    }
}