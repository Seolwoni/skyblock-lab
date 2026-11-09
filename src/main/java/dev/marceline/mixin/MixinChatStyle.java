package dev.marceline.mixin;

import dev.marceline.features.impl.visual.ChatStyle;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Chat backgrounds and scroll bar in the Marceline colours (see ChatStyle). */
@Mixin(ChatComponent.class)
public abstract class MixinChatStyle {
    private static final String FILL = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;fill(IIIII)V";

    // the scroll bar and the extra rows while the chat is open
    @ModifyArg(method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
        at = @At(value = "INVOKE", target = FILL), index = 4, require = 0)
    private int marceline$panelColor(int argb) {
        return ChatStyle.recolor(argb);
    }

    // each message line's background
    @ModifyArg(method = "lambda$extractRenderState$1", at = @At(value = "INVOKE", target = FILL), index = 4, require = 0)
    private static int marceline$lineColor(int argb) {
        return ChatStyle.recolor(argb);
    }
}
