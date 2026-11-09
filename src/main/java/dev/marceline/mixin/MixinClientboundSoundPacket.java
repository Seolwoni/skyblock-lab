package dev.marceline.mixin;

import dev.marceline.Marceline;
import dev.marceline.interfaces.IMutedSoundPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientboundSoundPacket.class)
public class MixinClientboundSoundPacket implements IMutedSoundPacket {
    @Unique private boolean marceline$muted;

    @Override
    public void marceline$mute() {
        marceline$muted = true;
    }

    @Inject(method = "handle(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V", at = @At("HEAD"), cancellable = true)
    private void skipMutedSound(ClientGamePacketListener listener, CallbackInfo ci) {
        if (marceline$muted && Marceline.getMc().packetProcessor().isSameThread()) ci.cancel();
    }
}