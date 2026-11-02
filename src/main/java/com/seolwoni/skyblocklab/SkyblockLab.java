package com.seolwoni.skyblocklab;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.seolwoni.skyblocklab.esp.Esp;
import com.seolwoni.skyblocklab.gfs.AutoGfs;
import com.seolwoni.skyblocklab.gui.ConfigScreen;
import com.seolwoni.skyblocklab.terminal.AutoTerminal;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** SkyBlock Lab: ESP, auto terminals and auto GFS for testing on a private map. Made by seolwoni. */
public class SkyblockLab implements ClientModInitializer {
	public static final String MOD_ID = "skyblocklab";
	public static final Logger LOGGER = LoggerFactory.getLogger("SkyBlock Lab");

	public static KeyMapping configKey;
	public static KeyMapping gfsKey;

	/** The screen that is open right now (tracked through Fabric's screen events). */
	public static Screen currentScreen;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		Config.load();

		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skyblocklab.config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
		gfsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skyblocklab.gfs", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			currentScreen = screen;
			ScreenEvents.remove(screen).register(s -> {
				if (currentScreen == s) currentScreen = null;
			});
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (configKey.consumeClick()) client.gui.setScreen(new ConfigScreen(null));
			while (gfsKey.consumeClick()) AutoGfs.refillNow(client);
			Esp.tick(client);
			AutoTerminal.tick(client);
			AutoGfs.tick(client);
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) AutoGfs.onChat(Minecraft.getInstance(), message.getString());
		});

		LOGGER.info("SkyBlock Lab loaded");
	}

	public static void chat(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§b[SBLab]§r " + text));
	}
}
