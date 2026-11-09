package dev.marceline.mixin;

import dev.marceline.features.impl.dev.Box3D;
import dev.marceline.features.impl.misc.Camera;
import dev.marceline.interfaces.IGlowingEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.*;

@Mixin(Entity.class)
public abstract class MixinEntity implements IGlowingEntity {
    @Unique private Color glowColor = Color.WHITE;
    @Unique private boolean glowForced = false;

    @Shadow
    public abstract float getYRot();

    /** Free Look: the mouse turns the camera, not the player. */
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void marceline$freeLookTurn(double dx, double dy, CallbackInfo ci) {
        if ((Object) this == net.minecraft.client.Minecraft.getInstance().player && dev.marceline.features.impl.misc.FreeLook.turn(dx, dy)) ci.cancel();
    }

    /** Free Look: while the camera lines up with us, it reads the free angles. */
    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void marceline$freeLookYaw(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (dev.marceline.features.impl.misc.FreeLook.aligning && dev.marceline.features.impl.misc.FreeLook.active && (Object) this == net.minecraft.client.Minecraft.getInstance().getCameraEntity())
            cir.setReturnValue(dev.marceline.features.impl.misc.FreeLook.yaw);
    }

    @Inject(method = "getViewXRot", at = @At("HEAD"), cancellable = true)
    private void marceline$freeLookPitch(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (dev.marceline.features.impl.misc.FreeLook.aligning && dev.marceline.features.impl.misc.FreeLook.active && (Object) this == net.minecraft.client.Minecraft.getInstance().getCameraEntity())
            cir.setReturnValue(dev.marceline.features.impl.misc.FreeLook.pitch);
    }

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void onGetTeamColorValue(CallbackInfoReturnable<Integer> cir) {
        if (marceline$isGlowing() && !Box3D.INSTANCE.enabled) {
            cir.setReturnValue(marceline$glowColor().getRGB());
        }
    }

    /**
     * Fixes high mouse input delay when riding an entity (MC-206540).
     *
     * @author isXander
     * @license LGPL-3.0
     * @see <a href="https://github.com/isXander/Debugify/blob/11bcb3c53dd6cda7475fe3738df64d3835ebd6d1/src/client/java/dev/isxander/debugify/client/mixins/basic/mc206540/EntityMixin.java">Original Debugify Implementation</a>
     */
    @Inject(method = "onPassengerTurned", at = @At("HEAD"))
    private void fixCameraMovement(Entity passenger, CallbackInfo ci) {
        if (!Camera.INSTANCE.enabled || !Camera.getInputFix().getValue()) return;
        if (!passenger.isAlwaysTicking()) return;
        passenger.setYBodyRot(getYRot());
        float f = Mth.wrapDegrees(passenger.getYRot() - getYRot());
        float g = Mth.clamp(f, -180.0F, 180.0F);
        passenger.yRotO += g - f;
        passenger.setYRot(passenger.getYRot() + g - f);
        passenger.setYHeadRot(passenger.getYRot());
    }

    @Override
    public Color marceline$glowColor() {
        return glowColor;
    }

    @Override
    public void marceline$glowColor(@NonNull Color color) {
        glowColor = color;
    }

    @Override
    public boolean marceline$isGlowing() {
        return glowForced;
    }

    @Override
    public void marceline$isGlowing(boolean value) {
        glowForced = value;
    }
}