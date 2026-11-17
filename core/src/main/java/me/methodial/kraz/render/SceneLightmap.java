package me.methodial.kraz.render;

import com.mojang.blaze3d.platform.NativeImage;
import me.methodial.kraz.scene.SceneEnvironment;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * A private 16×16 lightmap, so scenes look the same at midnight in a cave as they do on the title screen. It
 * follows the vanilla lightmap curve for a clear overworld sky without flicker, potion or dimension effects.
 */
final class SceneLightmap {
    private static @Nullable DynamicTexture texture;
    private static float builtSky = Float.NaN;
    private static float builtGamma = Float.NaN;

    private SceneLightmap() {
    }

    /** Returns the GL id of a lightmap matching the environment, rebuilding it only when the inputs change. */
    static int id(SceneEnvironment environment) {
        if (texture == null) {
            texture = new DynamicTexture(16, 16, false);
            texture.setFilter(true, false);
        }
        if (environment.skyBrightness() != builtSky || environment.gamma() != builtGamma) {
            builtSky = environment.skyBrightness();
            builtGamma = environment.gamma();
            fill(texture.getPixels(), builtSky, builtGamma);
            texture.upload();
        }
        return texture.getId();
    }

    private static @Nullable DynamicTexture white;

    /** A lightmap that leaves every surface at its own colour. */
    static int fullBright() {
        if (white == null) {
            white = new DynamicTexture(16, 16, false);
            white.getPixels().fillRect(0, 0, 16, 16, 0xFFFFFFFF);
            white.upload();
            white.setFilter(true, false);
        }
        return white.getId();
    }

    private static void fill(NativeImage image, float sky, float gamma) {
        float skyFactor = sky * 0.95f + 0.05f;
        Vector3f skyColor = new Vector3f(skyFactor, skyFactor, 1.0f).lerp(new Vector3f(1.0f), 0.35f);
        Vector3f color = new Vector3f();
        Vector3f grey = new Vector3f(0.75f);
        for (int skyLevel = 0; skyLevel < 16; skyLevel++) {
            for (int blockLevel = 0; blockLevel < 16; blockLevel++) {
                float skyBrightness = brightness(skyLevel) * skyFactor;
                float blockRed = brightness(blockLevel) * 1.5f;
                float blockGreen = blockRed * ((blockRed * 0.6f + 0.4f) * 0.6f + 0.4f);
                float blockBlue = blockRed * (blockRed * blockRed * 0.6f + 0.4f);
                color.set(blockRed, blockGreen, blockBlue);
                color.add(skyColor.x * skyBrightness, skyColor.y * skyBrightness, skyColor.z * skyBrightness);
                color.lerp(grey, 0.04f);
                clamp(color);
                color.set(curve(color.x, gamma), curve(color.y, gamma), curve(color.z, gamma));
                color.lerp(grey, 0.04f);
                clamp(color);
                int r = (int) (color.x * 255.0f), g = (int) (color.y * 255.0f), b = (int) (color.z * 255.0f);
                image.setPixelRGBA(blockLevel, skyLevel, 0xFF000000 | b << 16 | g << 8 | r);
            }
        }
    }

    private static float brightness(int level) {
        float f = level / 15.0f;
        return f / (4.0f - 3.0f * f);
    }

    private static float curve(float value, float gamma) {
        float inverse = 1.0f - value;
        float boosted = 1.0f - inverse * inverse * inverse * inverse;
        return value + (boosted - value) * gamma;
    }

    private static void clamp(Vector3f v) {
        v.set(Math.clamp(v.x, 0f, 1f), Math.clamp(v.y, 0f, 1f), Math.clamp(v.z, 0f, 1f));
    }
}
