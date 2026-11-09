package dev.marceline.features.impl.fishing

//#if CHEAT
import dev.marceline.Marceline.mc
import dev.marceline.config.types.DropdownSetting
import dev.marceline.config.types.KeybindSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.MainThreadPacketReceivedEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.features.Feature
import dev.marceline.features.impl.routes.AssistBridge
import dev.marceline.features.impl.routes.AutoRoutes
import dev.marceline.ui.notification.NotificationManager
import dev.marceline.utils.ChatUtils.modMessage
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.GuiUtils
import dev.marceline.utils.MathUtils
import dev.marceline.utils.PlayerUtils
import dev.marceline.utils.items.ItemUtils.skyblockId
import dev.marceline.utils.render.Render2D.drawString
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
        3 -> { // the sell menu: queue up the junk once its contents have arrived
            if (screen == null || screen.title.string == sellTitle) { if (sellTimer > 40) sellDone(p, false, "the sell menu didn't open"); return }
            // the window opens a moment before its items do: an empty inventory part means it isn't filled in yet
            val queue = sellQueueNow(p)
            if (queue.isEmpty() && sellTimer < 40) return
            sellQueue.clear(); sellQueue += queue
            sellPass++
            journal.log("sell pass $sellPass: ${queue.size} stacks to sell")
            sellStep = 4
            sellGap = delay(5, 14) // look at the menu for a moment first
        }
        4 -> { // click them one by one at a human pace, and check each one went
            if (screen == null) return sellDone(p, sellQueueNow(p).isEmpty(), if (sellQueueNow(p).isEmpty()) null else "the sell menu closed early")
            // the last click: wait until that stack is gone before the next (a click sent too early is ignored by the server)
            if (sellClicked >= 0) {
                val still = p.containerMenu.slots.getOrNull(sellClicked)?.item?.let { ! it.isEmpty && shouldSell(it) } == true
                if (still && sellWaitClick++ < 12) return
                if (still) journal.log("sell: slot $sellClicked didn't go, moving on")
                else sold++
                sellClicked = - 1; sellWaitClick = 0
            }
            val next = sellQueue.removeFirstOrNull()
            if (next == null) {
                // anything left (missed clicks, items that arrived late)? another pass, at most 3
                val left = sellQueueNow(p)
                if (left.isNotEmpty() && sellPass < 3) {
                    sellQueue += left; sellPass++
                    journal.log("sell pass $sellPass: ${left.size} stacks left")
                    sellGap = delay(6, 12)
                    return
                }
                journal.log("sell: done, $sold sold so far${if (left.isNotEmpty()) ", ${left.size} stacks wouldn't sell" else ""}")
                sellStep = 5
                sellGap = delay(4, 12)
                return
            }
            if (! p.containerMenu.slots.getOrNull(next)?.item.let { it != null && ! it.isEmpty && shouldSell(it) }) return
            GuiUtils.clickSlot(next, GuiUtils.ButtonType.LEFT)
            sellClicked = next
            sellWaitClick = 0
            sellGap = delay(4, 11) + if (RANDOM.nextInt(100) < 8) timing.uniform(6, 16) else 0
        }
        else -> sellDone(p, true, null)
    }
}

/** Sell-menu slot indexes of junk in your inventory part (never the hotbar), as the menu shows them now. */
internal fun AutoFishing.sellQueueNow(p: LocalPlayer): List<Int> = p.containerMenu.slots.filter {
    it.container is net.minecraft.world.entity.player.Inventory && it.containerSlot >= 9 && shouldSell(it.item)
}.map { it.index }

internal fun AutoFishing.sellDone(p: LocalPlayer, ok: Boolean, why: String?) {
    sellPass = 0; sellClicked = - 1; sellWaitClick = 0
    if (mc.gui.screen() != null) p.closeContainer()
    if (ok) sellFails = 0 else {
        sellFails++
        info("&eAuto Sell: $why${if (sellFails >= 3) " - turned off until the next start" else ""}.")
    }
    expectedSlot = - 1
    rod()?.let { hold(it) }
    state = FishingState.CAST
    returning = true
    wait = castWait()
    status = if (ok) "sold junk ($sold so far)" else "couldn't sell"
}

//#endif
