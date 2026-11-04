package com.github.noamm9.features.impl.fishing

//#if CHEAT
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.routes.AssistBridge
import com.github.noamm9.features.impl.routes.AutoRoutes
import com.github.noamm9.ui.notification.NotificationManager
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.Render2D.drawString
import java.util.Random
import kotlin.math.abs
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

// Auto Sell: sells only the items on your sell list through the SkyBlock Menu's Booster Cookie shop.

internal fun AutoFishing.sellNames() = sellList.value.split('|').filter { it.isNotBlank() }.toSet()

internal fun AutoFishing.itemKey(stack: net.minecraft.world.item.ItemStack): String? =
    if (stack.isEmpty) null else stack.hoverName.string.replace(Regex("§."), "").trim().ifBlank { null }

internal fun AutoFishing.freeSlots(p: LocalPlayer) = (0 until 36).count { p.inventory.getItem(it).isEmpty }

/** Only what's on your sell list is ever sold. */
internal fun AutoFishing.shouldSell(stack: net.minecraft.world.item.ItemStack): Boolean {
    if (stack.isEmpty || stack.item == Items.FISHING_ROD) return false
    val name = itemKey(stack) ?: return false
    return name in sellNames()
}

/** Inventory indexes (not the hotbar) holding junk. */
internal fun AutoFishing.sellables(p: LocalPlayer) = (9 until 36).filter { shouldSell(p.inventory.getItem(it)) }

internal fun AutoFishing.sell(p: LocalPlayer) {
    ownMoveTicks = 20
    sellTimer++
    if (sellTimer > 600) return sellDone(p, false, "selling took too long")
    if (sellGap > 0) { sellGap--; return }
    val screen = mc.gui.screen() as? AbstractContainerScreen<*>
    when (sellStep) {
        0 -> { // put the rod away and open the SkyBlock Menu
            if (p.fishing != null) { rod()?.let { hold(it); use(p) }; sellGap = delay(4, 10); return }
            hold(menuSlot.value - 1)
            sellGap = delay(3, 8)
            sellStep = 1
        }
        1 -> {
            use(p)
            sellTimer = 0
            sellStep = 2
        }
        2 -> { // wait for the menu, then click the cookie
            if (screen == null || screen is InventoryScreen) { if (sellTimer > 40) sellDone(p, false, "the SkyBlock Menu didn't open"); return }
            val menu = p.containerMenu
            val top = menu.slots.filter { it.container !is net.minecraft.world.entity.player.Inventory }
            val cookie = top.firstOrNull { it.item.item == Items.COOKIE || it.item.hoverName.string.contains("Cookie", true) }
                ?: return sellDone(p, false, "no Booster Cookie in the menu")
            sellTitle = screen.title.string
            GuiUtils.clickSlot(cookie.index, GuiUtils.ButtonType.LEFT)
            sellTimer = 0
            sellStep = 3
            sellGap = delay(2, 5)
        }
        3 -> { // the sell menu: queue up the junk
            if (screen == null || screen.title.string == sellTitle) { if (sellTimer > 40) sellDone(p, false, "the sell menu didn't open"); return }
            sellQueue.clear()
            for (slot in p.containerMenu.slots) {
                if (slot.container !is net.minecraft.world.entity.player.Inventory) continue
                if (slot.containerSlot < 9) continue // never the hotbar
                if (shouldSell(slot.item)) sellQueue += slot.index
            }
            sellStep = 4
            sellGap = delay(5, 14) // look at the menu for a moment first
        }
        4 -> { // click them one by one at a human pace
            if (screen == null) return sellDone(p, true, null)
            val next = sellQueue.removeFirstOrNull()
            if (next == null) {
                sellStep = 5
                sellGap = delay(4, 12)
                return
            }
            if (! p.containerMenu.slots.getOrNull(next)?.item.let { it != null && ! it.isEmpty }) return
            GuiUtils.clickSlot(next, GuiUtils.ButtonType.LEFT)
            sold++
            sellGap = delay(4, 11) + if (RANDOM.nextInt(100) < 8) timing.uniform(6, 16) else 0
        }
        else -> sellDone(p, true, null)
    }
}

internal fun AutoFishing.sellDone(p: LocalPlayer, ok: Boolean, why: String?) {
    if (mc.gui.screen() != null) p.closeContainer()
    if (ok) sellFails = 0 else {
        sellFails++
        info("&eAuto Sell: $why${if (sellFails >= 3) " - turned off until the next start" else ""}.")
    }
    expectedSlot = - 1
    rod()?.let { hold(it) }
    state = AutoFishing.State.CAST
    returning = true
    wait = castWait()
    status = if (ok) "sold junk ($sold so far)" else "couldn't sell"
}

//#endif
