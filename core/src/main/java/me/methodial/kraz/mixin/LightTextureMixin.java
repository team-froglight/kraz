package me.methodial.kraz.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import me.methodial.kraz.render.RenderHooks;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
abstract class LightTextureMixin {
    @Inject(method = "turnOnLightLayer", at = @At("TAIL"))
    private void kraz$useSceneLightmap(CallbackInfo ci) {
        int override = RenderHooks.lightmapOverride;
        if (override != -1) {
            RenderSystem.setShaderTexture(2, override);
        }
    }
}
