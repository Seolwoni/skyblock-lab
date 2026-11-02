package com.seolwoni.skyblocklab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seolwoni.skyblocklab.esp.Esp;

import net.minecraft.world.entity.Entity;

/** Gives ESP targets their outline color. */
@Mixin(Entity.class)
public class EntityMixin {
	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void skyblocklab$color(CallbackInfoReturnable<Integer> cir) {
		Integer c = Esp.color((Entity) (Object) this);
		if (c != null) cir.setReturnValue(c);
	}
}
