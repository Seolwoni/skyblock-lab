package com.seolwoni.skyblocklab;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Settings (config/skyblocklab.json). */
public class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("skyblocklab.json");
	private static Config instance = new Config();

	/** Master switch for everything. */
	public boolean modEnabled = true;

	// ESP (glowing outline through walls)
	public boolean espEnabled = true;
	public boolean espStarMobs = true;
	public boolean espBats = true;
	public boolean espWithers = true;
	public boolean espPlayers = false;
	public boolean espAllMobs = false;
	/** Extra name filters (case-insensitive, matched against the entity name or the name tag above it). */
	public List<String> espNames = new ArrayList<>();
	public int espRange = 64;
	public int colorStar = 0xFFAA00;
	public int colorBat = 0xFF55FF;
	public int colorWither = 0xAA00AA;
	public int colorPlayer = 0x55FFFF;
	public int colorOther = 0xFFFFFF;

	// Auto terminals
	public boolean termEnabled = true;
	public boolean termOrder = true;
	public boolean termColors = true;
	public boolean termStartsWith = true;
	public boolean termPanes = true;
	public boolean termRubix = true;
	public boolean termMelody = true;
	/** Wait this long after a terminal opens before the first click (ms). */
	public int termFirstDelay = 350;
	/** Wait this long between clicks (ms). */
	public int termClickDelay = 150;
	/** Give up on a click the server never answered after this long and allow it again (ms). */
	public int termResendAfter = 600;
	/** Use middle click (does not pick the item up client-side). Off = normal left click. */
	public boolean termMiddleClick = true;

	// Auto GFS (get from sacks)
	public boolean gfsEnabled = true;
	public boolean gfsOnDungeonStart = true;
	/** Also refill every N seconds while enabled (0 = only at dungeon start or with /sblab gfs). */
	public int gfsIntervalSec = 0;
	public boolean gfsPearls = true;
	public int gfsPearlTarget = 16;
	public boolean gfsLeaps = true;
	public int gfsLeapTarget = 16;
	public boolean gfsSuperboom = true;
	public int gfsSuperboomTarget = 64;
	public boolean gfsDecoy = false;
	public int gfsDecoyTarget = 64;

	public static Config get() {
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					Config c = GSON.fromJson(r, Config.class);
					if (c != null) instance = c;
				}
			}
		} catch (Exception e) {
			SkyblockLab.LOGGER.warn("Failed to load config", e);
		}
		if (instance.espNames == null) instance.espNames = new ArrayList<>();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (Exception e) {
			SkyblockLab.LOGGER.warn("Failed to save config", e);
		}
	}
}
