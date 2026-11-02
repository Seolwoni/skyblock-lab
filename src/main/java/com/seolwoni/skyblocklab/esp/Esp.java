package com.seolwoni.skyblocklab.esp;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.seolwoni.skyblocklab.Config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Chooses which entities glow (outline visible through walls) and in what color.
 * The two mixins ask {@link #color(Entity)}; the list is rebuilt every few ticks.
 */
public final class Esp {
	private static Map<Integer, Integer> colors = new HashMap<>();
	private static int timer;

	private Esp() {}

	/** The outline color for this entity, or null if it should not be highlighted by us. */
	public static Integer color(Entity e) {
		if (!Config.get().espEnabled) return null;
		return colors.get(e.getId());
	}

	public static void tick(Minecraft mc) {
		Config c = Config.get();
		LocalPlayer p = mc.player;
		if (!c.espEnabled || p == null) {
			if (!colors.isEmpty()) colors = new HashMap<>();
			return;
		}
		if (++timer % 5 != 0) return;

		Map<Integer, Integer> next = new HashMap<>();
		AABB area = p.getBoundingBox().inflate(c.espRange);
		List<Entity> all = p.level().getEntities(p, area);

		for (Entity e : all) {
			EntityType<?> type = e.getType();
			if (type == EntityType.ARMOR_STAND) {
				// Hypixel shows mob names on an armor stand above the real mob.
				if (!e.hasCustomName()) continue;
				String tag = e.getCustomName().getString();
				Integer col = null;
				if (c.espStarMobs && tag.contains("✯")) col = c.colorStar;
				else if (matchesName(c, tag)) col = c.colorOther;
				if (col == null) continue;
				Entity mob = mobBelow(e, all);
				if (mob != null) next.putIfAbsent(mob.getId(), col);
				continue;
			}
			if (c.espWithers && type == EntityType.WITHER) next.put(e.getId(), c.colorWither);
			else if (c.espBats && type == EntityType.BAT) next.put(e.getId(), c.colorBat);
			else if (c.espPlayers && type == EntityType.PLAYER) next.put(e.getId(), c.colorPlayer);
			else if (matchesName(c, e.getName().getString())) next.put(e.getId(), c.colorOther);
			else if (c.espAllMobs && e instanceof LivingEntity && type != EntityType.PLAYER) next.putIfAbsent(e.getId(), c.colorOther);
		}
		colors = next;
	}

	private static boolean matchesName(Config c, String name) {
		if (c.espNames.isEmpty()) return false;
		String n = name.toLowerCase(Locale.ROOT);
		for (String f : c.espNames) {
			if (!f.isBlank() && n.contains(f.toLowerCase(Locale.ROOT))) return true;
		}
		return false;
	}

	/** The closest living, non-armor-stand entity just under a name tag. */
	private static Entity mobBelow(Entity stand, List<Entity> all) {
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : all) {
			if (!(e instanceof LivingEntity) || e.getType() == EntityType.ARMOR_STAND || e.getType() == EntityType.PLAYER && e.isInvisible()) continue;
			double dx = e.getX() - stand.getX(), dz = e.getZ() - stand.getZ();
			double dy = stand.getY() - e.getY();
			if (Math.abs(dx) > 1.2 || Math.abs(dz) > 1.2 || dy < -0.5 || dy > 4) continue;
			double d = dx * dx + dz * dz + dy * dy * 0.25;
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}
}
