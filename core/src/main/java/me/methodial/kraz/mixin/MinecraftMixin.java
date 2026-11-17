package me.methodial.kraz.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.methodial.kraz.render.RenderHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void kraz$redirectMainTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget override = RenderHooks.targetOverride;
        if (override != null) {
            cir.setReturnValue(override);
        }
    }

    @Inject(method = "useShaderTransparency", at = @At("HEAD"), cancellable = true)
    private static void kraz$noFabulousTargets(CallbackInfoReturnable<Boolean> cir) {
        if (RenderHooks.targetOverride != null) {
            cir.setReturnValue(false);
        }
    }
}
