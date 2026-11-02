package com.seolwoni.skyblocklab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seolwoni.skyblocklab.esp.Esp;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Makes ESP targets glow (the outline is drawn through walls). */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void skyblocklab$glow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (Esp.color(entity) != null) cir.setReturnValue(true);
	}
}
