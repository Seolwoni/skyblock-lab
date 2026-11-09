@file:Suppress("UNCHECKED_CAST")

package dev.marceline.init

import dev.marceline.Marceline
import dev.marceline.Marceline.mc
import dev.marceline.commands.CommandBuilder
import dev.marceline.config.ConfigManager
import dev.marceline.event.EventBus.register
import dev.marceline.event.impl.RenderOverlayEvent
import dev.marceline.features.Feature
import dev.marceline.features.FeatureManager
import dev.marceline.init.types.*
import dev.marceline.ui.hud.HudEditorScreen
import dev.marceline.ui.utils.Resolution
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.classgraph.*
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import kotlin.system.measureTimeMillis

class ClassGraphInitializer {
    companion object {
        /** Features that threw while loading (shown by the game test, logged for players). */
        @JvmField val failedFeatures = mutableListOf<String>()
    }

    private val classLoader = Thread.currentThread().contextClassLoader

    fun initAll() = ClassGraph().enableClassInfo().ignoreClassVisibility().apply {
        acceptPackages(Marceline::class.java.`package`.name)
        rejectPackages("${Marceline::class.java.`package`.name}.lib")
        enableClassLoaders(classLoader)
    }.scan().use { scan ->
        val time = measureTimeMillis {
            scan.registerSelfInits()
            scan.registerCustomMenus()
            scan.registerCommands()
            scan.registerFeatures()
        }

        Marceline.logger.info("${this::class.simpleName} initialized in $time ms.")
    }

    private fun ScanResult.registerSelfInits() {
        getAllClassesImplementing(ISelfInit::class.java).forEach { ci ->
            (ci.getInstance<ISelfInit>()).init()
        }
    }

    private fun ScanResult.registerCustomMenus() {
        getAllClassesImplementing(ICustomMenu::class.java).forEach { ci ->
            ModCompatibility.customMenus.add(ci.getInstance())
        }
    }

    private fun ScanResult.registerCommands() {
        val providers = getAllClassesImplementing(ICommandProvider::class.java).map { it.getInstance<ICommandProvider>() }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            CommandBuilder.clear()
            providers.forEach { provider ->
                val commandBuilder = CommandBuilder()
                with(provider) { commandBuilder.command() }

                commandBuilder.build().forEach { root ->
                    dispatcher.register(root as LiteralArgumentBuilder<FabricClientCommandSource>)
                }
            }
        }
    }

    private fun ScanResult.registerFeatures() {
        getAllSubclasses(Feature::class.java).forEach { classInfo ->
            // Marceline: one broken feature (missing data, API change) must not stop the others from loading.
            val feature = runCatching { classInfo.getInstance<Feature>().apply(Feature::initialize) }.getOrElse {
                Marceline.logger.error("Feature ${classInfo.simpleName} failed to load and was skipped", it)
                failedFeatures += classInfo.simpleName
                return@forEach
            }
            FeatureManager.hudElements.addAll(feature.hudElements)
            FeatureManager.features.add(feature)
        }

        ConfigManager.load()

        register<RenderOverlayEvent> {
            if (mc.gui.screen() is HudEditorScreen) return@register
            if (dev.marceline.features.impl.routes.AssistBridge.hudHidden()) return@register // Assist Core's H key

            Resolution.push(event.context)
            val shown = FeatureManager.hudElements.filter { it.toggle && it.shouldDraw }
            if (dev.marceline.features.impl.dev.ClickGui.hudAvoidOverlap.value) {
                // panels that would overlap are drawn just below each other (positions in the config don't change)
                val rects = shown.map { h ->
                    val sw = h.width * h.scale; val sh = h.height * h.scale
                    dev.marceline.utils.HudLayout.R(h.x - (if (h.centered()) sw / 2f else 0f) - 5f * h.scale, h.y - 3.5f * h.scale, sw + 10f * h.scale, sh + 7f * h.scale)
                }
                val dy = dev.marceline.utils.HudLayout.offsets(rects, Resolution.height.toFloat())
                shown.forEachIndexed { i, h ->
                    if (dy[i] == 0f) return@forEachIndexed h.renderElement(event.context, false)
                    h.y += dy[i]
                    try { h.renderElement(event.context, false) } finally { h.y -= dy[i] }
                }
            }
            else shown.forEach { it.renderElement(event.context, false) }
            Resolution.pop(event.context)
        }
    }

    private fun <T> ClassInfo.getInstance() = Class.forName(name, true, classLoader).getDeclaredField("INSTANCE").get(null) as T
}