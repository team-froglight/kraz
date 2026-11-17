package me.methodial.kraz.render.post;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import me.methodial.kraz.Kraz;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/** Built-in post effects. */
public final class PostEffects {
    private static @Nullable ShaderInstance fxaaShader;
    private static @Nullable ShaderInstance vignetteShader;

    /**
     * Fast approximate antialiasing. A cheap complement to MSAA: it also softens edges inside cutout textures and
     * alpha-tested foliage, which multisampling cannot reach.
     */
    public static final PostEffect FXAA = new ShaderEffect(() -> fxaaShader);

    private PostEffects() {
    }

    /**
     * Darkens the image towards its corners.
     *
     * @param strength 0 (off) .. 1 (corners fully black)
     */
    public static PostEffect vignette(float strength) {
        return new ShaderEffect(() -> vignetteShader, shader -> shader.safeGetUniform("Strength").set(strength));
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(Kraz.MOD_ID, "fxaa"), DefaultVertexFormat.POSITION_TEX), s -> fxaaShader = s);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(Kraz.MOD_ID, "vignette"), DefaultVertexFormat.POSITION_TEX), s -> vignetteShader = s);
    }
}
