package com.seolwoni.skyblocklab.gfs;

import java.util.ArrayDeque;
import java.util.Deque;

import com.seolwoni.skyblocklab.Config;
import com.seolwoni.skyblocklab.SkyblockLab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/** Tops up dungeon items from the sacks with /gfs (at dungeon start, on a timer, or on a key). */
public final class AutoGfs {
	private static final long COMMAND_GAP_MS = 1500;
	private static final Deque<String> queue = new ArrayDeque<>();
	private static long lastSent, lastInterval;

	private AutoGfs() {}

	public static void onChat(Minecraft mc, String text) {
		Config c = Config.get();
		if (!c.modEnabled || !c.gfsEnabled || !c.gfsOnDungeonStart) return;
		String t = text.replaceAll("§.", "");
		if (t.startsWith("[NPC] Mort: Here, I found this map")) refillNow(mc);
	}

	public static void tick(Minecraft mc) {
		Config c = Config.get();
		LocalPlayer p = mc.player;
		if (p == null) {
			queue.clear();
			return;
		}
		long now = System.currentTimeMillis();
		if (c.modEnabled && c.gfsEnabled && c.gfsIntervalSec > 0 && now - lastInterval > c.gfsIntervalSec * 1000L) {
			lastInterval = now;
			if (queue.isEmpty()) refillNow(mc);
		}
		if (!queue.isEmpty() && now - lastSent >= COMMAND_GAP_MS) {
			lastSent = now;
			p.connection.sendCommand(queue.poll());
		}
	}

	/** Queue a /gfs for every enabled item that is below its target. */
	public static void refillNow(Minecraft mc) {
		Config c = Config.get();
		LocalPlayer p = mc.player;
		if (p == null || !c.modEnabled || !c.gfsEnabled) return;
		queue.clear();
		add(p, c.gfsPearls, "Ender Pearl", "ENDER_PEARL", c.gfsPearlTarget);
		add(p, c.gfsLeaps, "Spirit Leap", "SPIRIT_LEAP", c.gfsLeapTarget);
		add(p, c.gfsSuperboom, "Superboom TNT", "SUPERBOOM_TNT", c.gfsSuperboomTarget);
		add(p, c.gfsDecoy, "Decoy", "DECOY", c.gfsDecoyTarget);
		if (queue.isEmpty()) SkyblockLab.chat("GFS: 채울 아이템 없음");
	}

	private static void add(LocalPlayer p, boolean on, String name, String sackId, int target) {
		if (!on || target <= 0) return;
		int have = count(p, name);
		if (have < target) queue.add("gfs " + sackId + " " + (target - have));
	}

	private static int count(LocalPlayer p, String name) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) continue;
			if (s.getHoverName().getString().replaceAll("§.", "").trim().equalsIgnoreCase(name)) n += s.getCount();
		}
		return n;
	}
}
