package xyz.yourboykyle.secretroutes.utils;

import xyz.yourboykyle.secretroutes.config.SRMConfig;

/** Riftwalk: location comes from NoammAddons (no Hypixel Mod API mod needed). Server checking off = always "in dungeon". */
public class LocationUtils {
    public static void init() {
    }

    public static boolean isInDungeons() {
        return com.github.noamm9.utils.location.LocationUtils.inDungeon || SRMConfig.get().disableServerChecking;
    }

    public static boolean isOnSkyblock() {
        return com.github.noamm9.utils.location.LocationUtils.inSkyblock || SRMConfig.get().disableServerChecking;
    }

    public static void reset() {
    }
}
