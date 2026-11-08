package dev.riftwalk.gametest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.riftwalk.features.Feature;
import dev.riftwalk.features.FeatureManager;
import dev.riftwalk.features.impl.dev.ClickGui;
import dev.riftwalk.ui.clickgui.ClickGuiScreen;
import dev.riftwalk.ui.clickgui.SkyHanniScreen;
import dev.riftwalk.ui.clickgui.enums.CategoryType;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Quick health check in a real client: the world loads, both settings screens open,
 * every feature can be switched on, run for a few seconds and switched off, and Secret Routes is loaded.
 * Writes riftwalk-test-result.txt (CI copies it) and fails if anything went wrong.
 */
public class SmokeTest implements FabricClientGameTest {
	/** Features that would change the connection or the game in ways that make the rest of the test meaningless. */
	private static final Set<String> SKIP = Set.of("ModHider");

	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		// Full HD window, like a normal player's screen (GUI scale stays on auto).
		context.getInput().resizeWindow(1920, 1080);
		context.waitTicks(5);
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			context.waitTicks(20);

			// 1. Features registered
			int total = context.computeOnClient(mc -> FeatureManager.INSTANCE.getFeatures().size());
			log.add("features registered: " + total);
			if (total < 100) failures.add("only " + total + " features registered (expected 100+)");
			int routes = context.computeOnClient(mc -> (int) FeatureManager.INSTANCE.getFeaturesByCategory(CategoryType.DUNGEON).stream().filter(f -> "Routes".equals(f.getGroup())).count());
			log.add("routes features: " + routes);
			java.util.List<String> broken = dev.riftwalk.init.ClassGraphInitializer.failedFeatures;
			log.add("features that failed to load: " + broken);
			if (!broken.isEmpty()) failures.add("features failed to load: " + broken);
			if (routes < 2) failures.add("Dungeons > Routes has " + routes + " features (expected Secret Routes + Auto Routes)");

			// 2. Secret Routes started
			boolean srLoaded = context.computeOnClient(mc -> xyz.yourboykyle.secretroutes.Main.instance != null);
			log.add("secret routes loaded: " + srLoaded);
			if (!srLoaded) failures.add("Secret Routes did not initialise");

			// 3. Settings screens open and draw
			try {
				SkyHanniScreen.introFreeze = 0.5f;
				context.runOnClient(mc -> mc.gui.setScreen(ClickGui.createScreen()));
				context.waitTicks(5);
				context.takeScreenshot("rw-00-intro");
				SkyHanniScreen.introFreeze = -1f;
				context.runOnClient(mc -> mc.gui.setScreen(null));
				context.waitTicks(5);
			} catch (Throwable t) {
				failures.add("intro: " + t);
			}
			context.runOnClient(mc -> {
				dev.riftwalk.ui.notification.NotificationManager.INSTANCE.push("Auto Fishing stopped", "&fThe server moved or turned you.", 60000L);
				dev.riftwalk.ui.notification.NotificationManager.INSTANCE.push("Riftwalk", "&fReset &d38 &fcolours to the Void Rift defaults", 60000L);
			});
			checkScreen(context, "rw-01-skyhanni", true);
			checkScreen(context, "rw-02-classic", false);
			context.runOnClient(mc -> {
				for (Feature f : FeatureManager.INSTANCE.getFeatures()) {
					if (f.getClass().getSimpleName().equals("AutoFishing")) {
						SkyHanniScreen.lastCategory = CategoryType.FISHING;
						SkyHanniScreen.lastFeature = f;
					}
				}
			});
			checkScreen(context, "rw-04-settings", true);

			// 4. Every feature on, run, off
			List<String> toggled = new ArrayList<>();
			context.runOnClient(mc -> {
				for (Feature f : FeatureManager.INSTANCE.getFeatures()) {
					String id = f.getClass().getSimpleName();
					if (SKIP.contains(id) || f.enabled) continue;
					try {
						f.toggle();
						if (f.enabled) toggled.add(id);
					} catch (Throwable t) {
						failures.add("turning on " + id + ": " + t);
					}
				}
			});
			log.add("switched on: " + toggled.size());
			context.runOnClient(mc -> {
				dev.riftwalk.utils.ChatUtils.INSTANCE.modMessage("&aAuto Routes &fstarted in &bBlaze Room&f.");
				dev.riftwalk.utils.ChatUtils.INSTANCE.modMessage("&7Etherwarp landed, &e3 &7secrets left.");
				dev.riftwalk.utils.ChatUtils.INSTANCE.modMessage("&cCould not reach the next point, stopping.");
			});
			// a sidebar so the custom scoreboard shows in the shot
			try {
				sp.getServer().runOnServer(server -> {
					var src = server.createCommandSourceStack().withSuppressedOutput();
					for (String c : new String[] {
						"scoreboard objectives add rw dummy {\"text\":\"SKYBLOCK\",\"color\":\"yellow\",\"bold\":true}",
						"scoreboard objectives setdisplay sidebar rw",
						"scoreboard players set §7Nov_4th rw 6", "scoreboard players set §f_ rw 5",
						"scoreboard players set §bCrimson_Isle rw 4", "scoreboard players set §6Purse:_12,450,921 rw 3",
						"scoreboard players set §e_ rw 2", "scoreboard players set §eriftwalk.dev rw 1"
					}) server.getCommands().performPrefixedCommand(src, c);
				});
			} catch (Throwable t) { log.add("scoreboard setup: " + t); }
			context.waitTicks(100);
			context.takeScreenshot("rw-03-everything-on");
			try {
				context.runOnClient(mc -> mc.gui.setScreen(new dev.riftwalk.ui.hud.HudEditorScreen()));
				context.waitTicks(20);
				context.takeScreenshot("rw-05-hud-editor");
				context.runOnClient(mc -> mc.gui.setScreen(null));
				context.waitTicks(5);
			} catch (Throwable t) {
				failures.add("hud editor: " + t);
			}
			try {
				context.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
				context.waitTicks(20);
				String pause = context.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
				log.add("esc menu open: " + pause);
				if (!pause.equals("RiftPauseScreen")) failures.add("ESC menu is " + pause + " (expected RiftPauseScreen)");
				context.takeScreenshot("rw-06-esc-menu");
				context.runOnClient(mc -> mc.gui.setScreen(null));
				context.waitTicks(5);
			} catch (Throwable t) {
				failures.add("esc menu: " + t);
			}
			context.runOnClient(mc -> {
				for (Feature f : FeatureManager.INSTANCE.getFeatures()) {
					String id = f.getClass().getSimpleName();
					if (!toggled.contains(id)) continue;
					try {
						f.toggle();
					} catch (Throwable t) {
						failures.add("turning off " + id + ": " + t);
					}
				}
			});
			context.waitTicks(20);

			// 5. All features off / back on restores exactly the same set
			String master = context.computeOnClient(mc -> {
				java.util.Set<String> before = new java.util.HashSet<>();
				for (Feature f : FeatureManager.INSTANCE.getFeatures()) if (f.enabled) before.add(f.getJsonName());
				dev.riftwalk.features.MasterSwitch.INSTANCE.toggle();
				int stillOn = 0;
				java.util.Set<String> fixed = java.util.Set.of("ClickGui", "WebSocketFeature", "DoorFix", "ClassColors", "ModHider");
				for (Feature f : FeatureManager.INSTANCE.getFeatures()) if (f.enabled && !fixed.contains(f.getClass().getSimpleName())) stillOn++;
				dev.riftwalk.features.MasterSwitch.INSTANCE.toggle();
				java.util.Set<String> after = new java.util.HashSet<>();
				for (Feature f : FeatureManager.INSTANCE.getFeatures()) if (f.enabled) after.add(f.getJsonName());
				return "on before " + before.size() + ", on while off " + stillOn + ", restored " + before.equals(after);
			});
			log.add("all on/off: " + master);
			if (!master.contains("on while off 0") || !master.endsWith("restored true")) failures.add("all on/off did not work: " + master);

			boolean alive = context.computeOnClient(mc -> mc.player != null && mc.level != null);
			if (!alive) failures.add("player or level gone after toggling features");
		} catch (Throwable t) {
			failures.add("test crashed: " + t);
		}

		try {
			context.waitTicks(20);
			context.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
			context.waitTicks(30);
			String title = context.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
			log.add("title screen open: " + title);
			if (!title.equals("RiftTitleScreen")) failures.add("title screen is " + title + " (expected RiftTitleScreen)");
			context.takeScreenshot("rw-07-title");
		} catch (Throwable t) {
			failures.add("title screen: " + t);
		}

		write();
		if (!failures.isEmpty()) throw new AssertionError("Riftwalk smoke test failed:\n" + String.join("\n", failures));
	}

	private void checkScreen(ClientGameTestContext context, String shot, boolean skyHanni) {
		try {
			context.runOnClient(mc -> mc.gui.setScreen(skyHanni ? ClickGui.createScreen() : new ClickGuiScreen()));
			context.waitTicks(20);
			String open = context.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
			log.add(shot + " open: " + open);
			if (skyHanni && !open.equals(SkyHanniScreen.class.getSimpleName())) failures.add("SkyHanni screen did not open (got " + open + ")");
			if (!skyHanni && !open.equals(ClickGuiScreen.class.getSimpleName())) failures.add("classic screen did not open (got " + open + ")");
			context.takeScreenshot(shot);
			context.runOnClient(mc -> mc.gui.setScreen(null));
			context.waitTicks(5);
		} catch (Throwable t) {
			failures.add(shot + ": " + t);
		}
	}

	private void write() {
		try {
			Path out = FabricLoader.getInstance().getGameDir().resolve("riftwalk-test-result.txt");
			List<String> lines = new ArrayList<>(log);
			lines.add(failures.isEmpty() ? "RESULT: PASS" : "RESULT: FAIL");
			lines.addAll(failures);
			Files.write(out, lines, StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}
}
