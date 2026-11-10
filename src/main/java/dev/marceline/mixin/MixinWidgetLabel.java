package dev.marceline.mixin;

import dev.marceline.ui.menu.OptionsFont;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Button and slider labels on the game's Options screens in the Marceline menu font (see OptionsFont). */
@Mixin(AbstractWidget.class)
public abstract class MixinWidgetLabel {
    @ModifyVariable(method = "extractScrollingStringOverContents", at = @At("HEAD"), argsOnly = true)
    private Component marceline$label(Component label) {
        return OptionsFont.label(label);
    }
}
