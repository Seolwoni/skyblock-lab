package dev.marceline.mixin;

import dev.marceline.features.impl.visual.MenuScreens;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The selected row in the game's lists (worlds, servers, resource packs, languages...): a gold outline instead of
 * white / grey, a dark wine fill instead of black (see MenuScreens.selection*).
 */
@Mixin(AbstractSelectionList.class)
public abstract class MixinListSelection {
    private static final String FILL = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V";

    @ModifyArg(method = "extractSelection", at = @At(value = "INVOKE", target = FILL, ordinal = 0), index = 4, require = 0)
    private int marceline$outline(int argb) {
        return MenuScreens.selectionOutline(argb);
    }

    @ModifyArg(method = "extractSelection", at = @At(value = "INVOKE", target = FILL, ordinal = 1), index = 4, require = 0)
    private int marceline$inside(int argb) {
        return MenuScreens.selectionInside(argb);
    }
}
