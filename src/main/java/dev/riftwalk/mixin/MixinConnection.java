package dev.riftwalk.mixin;

import dev.riftwalk.Riftwalk;
import dev.riftwalk.debug.TestGround;
import dev.riftwalk.event.EventBus;
import dev.riftwalk.event.impl.PacketEvent;
import dev.riftwalk.event.impl.TickEvent;
import dev.riftwalk.features.impl.general.CommandShortcuts;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class MixinConnection {
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void onPacketSent(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (EventBus.post(new PacketEvent.Sent(packet))) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "sendPacket", at = @At("HEAD"), argsOnly = true)
    private Packet<?> rewriteCommandSuggestion(Packet<?> packet) {
        if (packet instanceof ServerboundCommandSuggestionPacket suggestionPacket) {
            return CommandShortcuts.INSTANCE.rewriteSuggestionPacket(suggestionPacket);
        }
        return packet;
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"), cancellable = true)
    private void channelRead0(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundPingPacket pingPacket && pingPacket.getId() != 0) {
            if (! TestGround.INSTANCE.getTick()) {
                Riftwalk.getMc().execute(() -> EventBus.post(TickEvent.Server.INSTANCE));
            }
        }

        if (EventBus.post(new PacketEvent.Received(packet))) {
            ci.cancel();
        }
    }
}