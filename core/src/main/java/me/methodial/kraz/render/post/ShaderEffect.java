package me.methodial.kraz.render.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link PostEffect} backed by a core shader. To write your own:
 *
 * <ol>
 *   <li>Add {@code assets/<modid>/shaders/core/<name>.json} with {@code "vertex": "kraz:post"} and your own
 *       fragment shader. The scene image is {@code Sampler0}; {@code texCoord} is the 0..1 screen coordinate.</li>
 *   <li>Load it in {@code RegisterShadersEvent} with {@code DefaultVertexFormat.POSITION_TEX} and keep the instance.</li>
 *   <li>Wrap it: {@code new ShaderEffect(() -> myShader)}.</li>
 * </ol>
 *
 * If the shader declares them, {@code InSize} (vec2, pixels) and {@code Time} (float, seconds) are filled in
 * automatically.
 */
public class ShaderEffect implements PostEffect {
    private final Supplier<@Nullable ShaderInstance> shader;
    private final @Nullable Consumer<ShaderInstance> uniforms;

    public ShaderEffect(Supplier<@Nullable ShaderInstance> shader) {
        this(shader, null);
    }

    /** @param uniforms called before every draw to set the shader's custom uniforms */
    public ShaderEffect(Supplier<@Nullable ShaderInstance> shader, @Nullable Consumer<ShaderInstance> uniforms) {
        this.shader = shader;
        this.uniforms = uniforms;
    }

    @Override
    public boolean apply(RenderTarget in, RenderTarget out, float timeSeconds) {
        ShaderInstance instance = shader.get();
        if (instance == null) {
            return false;
        }
        out.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, in.getColorTextureId());
        instance.safeGetUniform("InSize").set((float) in.width, (float) in.height);
        instance.safeGetUniform("Time").set(timeSeconds);
        if (uniforms != null) {
            uniforms.accept(instance);
        }
        RenderSystem.setShader(() -> instance);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(-1, -1, 0).setUv(0, 0);
        builder.addVertex(1, -1, 0).setUv(1, 0);
        builder.addVertex(1, 1, 0).setUv(1, 1);
        builder.addVertex(-1, 1, 0).setUv(0, 1);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        return true;
    }
}
