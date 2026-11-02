package com.seolwoni.assistpath;

/** Compile-only stub of the Assist mods API (excluded from the jar). The real classes come from Assist Core / Assist Path. */
public record PathOptions(boolean allowBreak, boolean allowPlace, boolean allowParkour, boolean avoidWater) {
	public static final PathOptions WALK = new PathOptions(false, false, false, false);
}
