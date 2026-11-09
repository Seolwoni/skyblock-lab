package dev.marceline.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.marceline.features.impl.visual.ChatStyle;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The thin bar at the left of tagged chat lines (system / modified messages) in wine instead of grey (see ChatStyle). */
@Mixin(GuiMessageTag.class)
public abstract class MixinGuiMessageTag {
    @ModifyReturnValue(method = "indicatorColor", at = @At("RETURN"))
    private int marceline$indicator(int rgb) {
        return ChatStyle.tagColor(rgb);
    }
}
