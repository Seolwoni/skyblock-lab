package com.seolwoni.skyblocklab.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Reads a terminal's slots and works out which slots to click. Pure logic, no clicking. */
public final class Solvers {
	public enum Kind {
		ORDER(true), COLORS(false), STARTS_WITH(false), PANES(false), RUBIX(true), MELODY(true);

		public final boolean oneAtATime;

		Kind(boolean oneAtATime) {
			this.oneAtATime = oneAtATime;
		}
	}

	/** A click on a container slot; right = right click (only rubix uses it). */
	public record Click(int slot, boolean right) {}

	private static final String[] COLORS = {
		"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink",
		"gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
	};
	/** Rubix colors in the order a left click cycles through them. */
	private static final String[] RUBIX_ORDER = {"red", "orange", "yellow", "green", "blue"};
	private static final int[] RUBIX_SLOTS = {12, 13, 14, 21, 22, 23, 30, 31, 32};

	private Solvers() {}

	public static Kind kindOf(String title) {
		String t = strip(title).trim();
		if (t.equals("Click in order!")) return Kind.ORDER;
		if (t.startsWith("Select all the ") && t.endsWith(" items!")) return Kind.COLORS;
		if (t.startsWith("What starts with:")) return Kind.STARTS_WITH;
		if (t.equals("Correct all the panes!")) return Kind.PANES;
		if (t.equals("Change all to same color!")) return Kind.RUBIX;
		if (t.equals("Click the button on time!")) return Kind.MELODY;
		return null;
	}

	public static List<Click> solve(Kind kind, String title, ItemStack[] items, int size) {
		return switch (kind) {
			case ORDER -> order(items, size);
			case COLORS -> colors(strip(title), items, size);
			case STARTS_WITH -> startsWith(strip(title), items, size);
			case PANES -> panes(items, size);
			case RUBIX -> rubix(items, size);
			case MELODY -> melody(items, size);
		};
	}

	/** Red panes numbered by stack size: click the smallest. */
	private static List<Click> order(ItemStack[] items, int size) {
		int best = -1;
		for (int i = 0; i < size; i++) {
			if (!id(items[i]).equals("red_stained_glass_pane")) continue;
			if (best < 0 || items[i].getCount() < items[best].getCount()) best = i;
		}
		return best < 0 ? List.of() : List.of(new Click(best, false));
	}

	/** "Select all the LIGHT BLUE items!": every item of that color that is not selected (glowing) yet. */
	private static List<Click> colors(String title, ItemStack[] items, int size) {
		String target = title.substring("Select all the ".length(), title.length() - " items!".length())
				.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (target.equals("silver")) target = "light_gray";
		List<Click> out = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			if (!inner(i, size) || items[i].isEmpty() || items[i].hasFoil()) continue;
			if (target.equals(colorOf(id(items[i])))) out.add(new Click(i, false));
		}
		return out;
	}

	/** "What starts with: 'S'?": every item whose name starts with that letter and is not selected yet. */
	private static List<Click> startsWith(String title, ItemStack[] items, int size) {
		int q1 = title.indexOf('\''), q2 = title.lastIndexOf('\'');
		if (q1 < 0 || q2 <= q1 + 1) return List.of();
		String letter = title.substring(q1 + 1, q2).trim().toLowerCase(Locale.ROOT);
		List<Click> out = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			if (!inner(i, size) || items[i].isEmpty() || items[i].hasFoil()) continue;
			String name = strip(items[i].getHoverName().getString()).trim().toLowerCase(Locale.ROOT);
			if (!name.isEmpty() && name.startsWith(letter)) out.add(new Click(i, false));
		}
		return out;
	}

	/** Every red pane turns green when clicked. */
	private static List<Click> panes(ItemStack[] items, int size) {
		List<Click> out = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			if (id(items[i]).equals("red_stained_glass_pane")) out.add(new Click(i, false));
		}
		return out;
	}

	/** Pick the target color needing the fewest clicks, then click each pane toward it (right click goes backwards). */
	private static List<Click> rubix(ItemStack[] items, int size) {
		int n = RUBIX_ORDER.length;
		int[] cur = new int[RUBIX_SLOTS.length];
		for (int k = 0; k < RUBIX_SLOTS.length; k++) {
			int slot = RUBIX_SLOTS[k];
			if (slot >= size) return List.of();
			cur[k] = indexOf(RUBIX_ORDER, colorOf(id(items[slot])));
			if (cur[k] < 0) return List.of();
		}
		int bestTarget = 0, bestCost = Integer.MAX_VALUE;
		for (int t = 0; t < n; t++) {
			int cost = 0;
			for (int v : cur) {
				int d = Math.floorMod(t - v, n);
				cost += Math.min(d, n - d);
			}
			if (cost < bestCost) {
				bestCost = cost;
				bestTarget = t;
			}
		}
		List<Click> out = new ArrayList<>();
		for (int k = 0; k < cur.length; k++) {
			int d = Math.floorMod(bestTarget - cur[k], n);
			if (d == 0) continue;
			out.add(new Click(RUBIX_SLOTS[k], d > n - d));
		}
		return out;
	}

	/** Click a row's button when its moving lime pane lines up with the magenta pane in the top row. */
	private static List<Click> melody(ItemStack[] items, int size) {
		int target = -1;
		for (int col = 0; col < 9 && col < size; col++) {
			if (id(items[col]).equals("magenta_stained_glass_pane")) target = col;
		}
		if (target < 0) return List.of();
		for (int row = 1; (row + 1) * 9 <= size; row++) {
			int lime = -1;
			for (int col = 1; col <= 5; col++) {
				if (id(items[row * 9 + col]).equals("lime_stained_glass_pane")) lime = col;
			}
			if (lime == target) {
				int button = row * 9 + 7;
				if (!items[button].isEmpty()) return List.of(new Click(button, false));
			}
		}
		return List.of();
	}

	/** Inside the border of a chest GUI (not the outer ring of filler panes). */
	private static boolean inner(int slot, int size) {
		int row = slot / 9, col = slot % 9, rows = size / 9;
		return row >= 1 && row <= rows - 2 && col >= 1 && col <= 7;
	}

	/** The dye color of an item id like light_blue_wool, or a few old dye names. */
	static String colorOf(String id) {
		switch (id) {
			case "lapis_lazuli": return "blue";
			case "cocoa_beans": return "brown";
			case "bone_meal": return "white";
			case "ink_sac": return "black";
			default: break;
		}
		for (String c : COLORS) {
			if (id.startsWith(c + "_")) return c;
		}
		return null;
	}

	static String id(ItemStack s) {
		return s == null || s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
	}

	/** Enough of a slot to tell whether the server changed it. */
	static String snapshot(ItemStack s) {
		return id(s) + "x" + (s == null ? 0 : s.getCount()) + (s != null && s.hasFoil() ? "*" : "") + "|" + (s == null || s.isEmpty() ? "" : s.getHoverName().getString());
	}

	private static int indexOf(String[] a, String v) {
		for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
		return -1;
	}

	private static String strip(String s) {
		return s.replaceAll("§.", "");
	}
}
