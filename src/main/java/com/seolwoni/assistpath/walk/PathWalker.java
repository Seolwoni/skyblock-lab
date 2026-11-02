package com.seolwoni.assistpath.walk;

import java.util.List;

import com.seolwoni.assistpath.PathOptions;
import com.seolwoni.assistpath.goals.Goal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** Compile-only stub of the Assist mods API (excluded from the jar). The real classes come from Assist Core / Assist Path. */
public final class PathWalker {
	public PathWalker(Goal goal, PathOptions options, int maxReplans) {
	}

	public String failReason() {
		return null;
	}

	public List<BlockPos> cells() {
		return List.of();
	}

	/** Returns Action.RUN (0), DONE (1) or FAIL (2). */
	public int tick(Minecraft mc, LocalPlayer p) {
		return 2;
	}
}
