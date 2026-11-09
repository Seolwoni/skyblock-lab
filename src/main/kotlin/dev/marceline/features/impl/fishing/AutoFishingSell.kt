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
    if (sellRefused.isNotEmpty() && sellKey(stack) in sellRefused) return false
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
            // what to sell, read now by name from the plain inventory: inside the sell menu the server shows the
            // same items with other names / lore, so there they are recognised by their SkyBlock id instead
            sellSnapshot.clear()
            for (i in 9 until 36) p.inventory.getItem(i).let { if (shouldSell(it)) sellSnapshot[i] = sellKey(it) }
            sellGap = delay(3, 8)
            sellStep = 1
        }
        1 -> {
            use(p)
            sellTimer = 0
            sellStep = 2
        }
        2 -> { // wait for the menu and its items, then click the cookie
            if (screen == null || screen is InventoryScreen) { if (sellTimer > 40) sellDone(p, false, "the SkyBlock Menu didn't open"); return }
            val menu = p.containerMenu
            val top = menu.slots.filter { it.container !is net.minecraft.world.entity.player.Inventory }
            // the window opens a moment before its items arrive: don't call the cookie missing before then
            if (top.all { it.item.isEmpty } && sellTimer < 40) return
            val cookie = top.firstOrNull { it.item.item == Items.COOKIE || it.item.hoverName.string.contains("Cookie", true) }
            if (cookie == null) {
                if (sellTimer < 20) return // items still coming in
                return sellDone(p, false, "no Booster Cookie in the menu")
            }
            sellTitle = screen.title.string
            GuiUtils.clickSlot(cookie.index, GuiUtils.ButtonType.LEFT)
            sellTimer = 0
            sellStep = 3
            sellGap = delay(2, 5)
        }
        3 -> { // the sell menu: wait until its contents have arrived and it stays the same window
            if (screen == null || screen.title.string == sellTitle) { if (sellTimer > 40) sellDone(p, false, "the sell menu didn't open"); return }
            val id = p.containerMenu.containerId
            if (id != sellMenuId) { sellMenuId = id; sellWaitClick = 0; return } // a new window: give it a moment
            if (sellWaitClick++ < 4) return
            sellWaitClick = 0
            // queued as inventory slots (9-35), not window slots: those stay put if the server swaps the window
            val queue = sellInventorySlots(p)
            if (queue.isEmpty() && sellTimer < 40) return
            if (queue.isEmpty() && sellSnapshot.isNotEmpty()) {
                // tell what the menu shows instead, for the log
                val (slot, key) = sellSnapshot.entries.first()
                val now = p.inventory.getItem(slot)
                journal.log("sell: ${sellSnapshot.size} stacks to sell before the menu, none recognised in it - slot $slot was $key, now ${sellKey(now)} \"${now.hoverName.string}\"")
            }
            sellQueue.clear(); sellQueue += queue
            sellPass++
            soldThisRun = 0
            journal.log("sell pass $sellPass: ${queue.size} stacks to sell  (menu \"${screen.title.string}\", window $id, ${p.containerMenu.slots.size} slots)")
            sellStep = 4
            sellGap = delay(5, 14) // look at the menu for a moment first
        }
        4 -> { // click them one by one at a human pace, and check each one went
            if (screen == null) return sellDone(p, sellInventorySlots(p).isEmpty(), if (sellInventorySlots(p).isEmpty()) null else "the sell menu closed early")
            if (p.containerMenu.containerId != sellMenuId) {
                journal.log("sell: the window changed (${sellMenuId} -> ${p.containerMenu.containerId}), \"${screen.title.string}\"")
                sellMenuId = p.containerMenu.containerId
            }
            // the last click: wait until that stack is gone before the next (a click sent too early is ignored by the server)
            if (sellClicked >= 0) {
                val still = stillToSell(p, sellClicked)
                if (still && sellWaitClick++ < 12) return
                if (still) {
                    // the menu won't take this one (soulbound, not sellable there...): leave it be from now on
                    val key = sellSnapshot[sellClicked]
                    journal.log("sell: inventory slot $sellClicked ($key, \"${p.inventory.getItem(sellClicked).hoverName.string}\") didn't go - not selling it again this session")
                    key?.let { sellRefused += it }
                    sellQueue.removeAll { sellSnapshot[it] == key }
                }
                else { sold++; soldThisRun++ }
                sellClicked = - 1; sellWaitClick = 0
            }
            val next = sellQueue.removeFirstOrNull()
            if (next == null) {
                // anything left (missed clicks, items that arrived late)? another pass, at most 3
                val left = sellInventorySlots(p)
                if (left.isNotEmpty() && sellPass < 3 && soldThisRun > 0) {
                    sellQueue += left; sellPass++
                    journal.log("sell pass $sellPass: ${left.size} stacks left")
                    sellGap = delay(6, 12)
                    return
                }
                journal.log("sell: done, $sold sold so far (this time $soldThisRun)${if (left.isNotEmpty()) ", ${left.size} stacks wouldn't sell" else ""}")
                // nothing went at all: the menu doesn't take them - say so instead of coming straight back to it
                if (soldThisRun == 0 && sellSnapshot.isNotEmpty()) return sellDone(p, false, if (left.isNotEmpty()) "the sell menu didn't take anything" else "couldn't find the items to sell in the sell menu")
                sellStep = 5
                sellGap = delay(4, 12)
                return
            }
            if (! stillToSell(p, next)) return
            val slot = p.containerMenu.slots.firstOrNull { it.container is net.minecraft.world.entity.player.Inventory && it.containerSlot == next }
            if (slot == null) { journal.log("sell: inventory slot $next isn't in this window"); return }
            GuiUtils.clickSlot(slot.index, GuiUtils.ButtonType.LEFT)
            sellClicked = next
            sellWaitClick = 0
            sellGap = delay(4, 11) + if (RANDOM.nextInt(100) < 8) timing.uniform(6, 16) else 0
        }
        else -> sellDone(p, true, null)
    }
}

/** What an item is for selling: its SkyBlock id, or the Minecraft item when it has none. */
internal fun AutoFishing.sellKey(stack: net.minecraft.world.item.ItemStack): String =
    if (stack.isEmpty) "empty" else stack.skyblockId.ifBlank { net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString() }

/** The slot still holds what was there to sell when the menu was opened. */
internal fun AutoFishing.stillToSell(p: LocalPlayer, slot: Int): Boolean {
    val want = sellSnapshot[slot] ?: return false
    val now = p.inventory.getItem(slot)
    return ! now.isEmpty && sellKey(now) == want
}

/** Inventory slots (9-35, never the hotbar) still holding what was there to sell before the menu opened. */
internal fun AutoFishing.sellInventorySlots(p: LocalPlayer): List<Int> = sellSnapshot.keys.sorted().filter { stillToSell(p, it) }


internal fun AutoFishing.sellDone(p: LocalPlayer, ok: Boolean, why: String?) {
    sellPass = 0; sellClicked = - 1; sellWaitClick = 0; sellMenuId = - 1
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
