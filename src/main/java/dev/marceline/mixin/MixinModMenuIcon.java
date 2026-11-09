package dev.marceline.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Mod Menu asks for an icon of 64 x the GUI Scale *setting* (0 when it is Auto) and draws it 32 GUI units wide
 * with no smoothing, so the picture is either a small one blown up (blocky) or a big one squeezed (jagged).
 * Ask for the size it is really drawn at instead: 32 GUI units in screen pixels. The icon map in
 * fabric.mod.json5 has a file for each of those sizes.
 * Only applies when Mod Menu is installed; any version whose entry calls Mod.getIcon(handler, size).
 */
@Pseudo
@Mixin(targets = "com.terraformersmc.modmenu.gui.widget.entries.ModListEntry", remap = false)
public class MixinModMenuIcon {
    @ModifyArg(method = "getIconTexture", at = @At(value = "INVOKE", target = "Lcom/terraformersmc/modmenu/util/mod/Mod;getIcon"), index = 1, require = 0)
    private int marceline$drawnIconSize(int asked) {
        long drawn = Math.round(32.0 * Minecraft.getInstance().getWindow().getGuiScale());
        return (int) Math.max(16, drawn);
    }
}
