package com.seolwoni.skyblocklab.terminal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.seolwoni.skyblocklab.Config;
import com.seolwoni.skyblocklab.SkyblockLab;
import com.seolwoni.skyblocklab.terminal.Solvers.Click;
import com.seolwoni.skyblocklab.terminal.Solvers.Kind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Solves F7 terminals and clicks the answers.
 * After each click the slot is "pending" until the server changes it, so nothing is clicked twice.
 */
public final class AutoTerminal {
	private static Screen screen;
	private static Kind kind;
	private static long openedAt, lastClickAt;
	/** slot -> {click time, snapshot of the slot when clicked} */
	private static final Map<Integer, Object[]> pending = new HashMap<>();

	private AutoTerminal() {}

	public static void tick(Minecraft mc) {
		Config c = Config.get();
		Screen s = SkyblockLab.currentScreen;
		if (!c.termEnabled || mc.player == null || mc.gameMode == null || !(s instanceof AbstractContainerScreen<?> cs)) {
			screen = null;
			pending.clear();
			return;
		}
		long now = System.currentTimeMillis();
		if (s != screen) {
			screen = s;
			kind = Solvers.kindOf(s.getTitle().getString());
			openedAt = now;
			pending.clear();
		}
		if (kind == null || !enabled(c, kind)) return;
		if (now - openedAt < c.termFirstDelay || now - lastClickAt < c.termClickDelay) return;

		AbstractContainerMenu menu = cs.getMenu();
		int size = menu.slots.size() - 36;
		if (size <= 0) return;
		ItemStack[] items = new ItemStack[size];
		for (int i = 0; i < size; i++) items[i] = menu.slots.get(i).getItem();

		// A pending click is done once the server changed that slot; retry it if nothing happened for too long.
		pending.entrySet().removeIf(e -> {
			int slot = e.getKey();
			boolean changed = slot >= size || !Solvers.snapshot(items[slot]).equals(e.getValue()[1]);
			return changed || now - (long) e.getValue()[0] > c.termResendAfter;
		});
		// Order, rubix and melody depend on the previous click, so wait for the server before the next one.
		if (kind.oneAtATime && !pending.isEmpty()) return;

		List<Click> answer = Solvers.solve(kind, s.getTitle().getString(), items, size);
		for (Click click : answer) {
			if (pending.containsKey(click.slot())) continue;
			send(mc, menu, click, c.termMiddleClick);
			pending.put(click.slot(), new Object[] {now, Solvers.snapshot(items[click.slot()])});
			lastClickAt = now;
			return;
		}
	}

	private static void send(Minecraft mc, AbstractContainerMenu menu, Click click, boolean middle) {
		if (click.right()) {
			mc.gameMode.handleContainerInput(menu.containerId, click.slot(), 1, ContainerInput.PICKUP, mc.player);
		} else if (middle) {
			mc.gameMode.handleContainerInput(menu.containerId, click.slot(), 2, ContainerInput.CLONE, mc.player);
		} else {
			mc.gameMode.handleContainerInput(menu.containerId, click.slot(), 0, ContainerInput.PICKUP, mc.player);
		}
	}

	private static boolean enabled(Config c, Kind k) {
		return switch (k) {
			case ORDER -> c.termOrder;
			case COLORS -> c.termColors;
			case STARTS_WITH -> c.termStartsWith;
			case PANES -> c.termPanes;
			case RUBIX -> c.termRubix;
			case MELODY -> c.termMelody;
		};
	}
}
