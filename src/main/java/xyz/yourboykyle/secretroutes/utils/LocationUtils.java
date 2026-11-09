package xyz.yourboykyle.secretroutes.utils;

import xyz.yourboykyle.secretroutes.config.SRMConfig;

/** Marceline: location comes from Marceline (no Hypixel Mod API mod needed). Server checking off = always "in dungeon". */
public class LocationUtils {
    public static void init() {
    }

    public static boolean isInDungeons() {
        return dev.marceline.utils.location.LocationUtils.inDungeon || SRMConfig.get().disableServerChecking;
    }

    public static boolean isOnSkyblock() {
        return dev.marceline.utils.location.LocationUtils.inSkyblock || SRMConfig.get().disableServerChecking;
    }

    public static void reset() {
    }
}
