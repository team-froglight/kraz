package me.methodial.kraz.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.jetbrains.annotations.Nullable;

/**
 * State read by Kraz's mixins while a scene is being drawn offscreen. Vanilla render types bind "the main render
 * target" and "the lightmap" by themselves, so redirecting them is the only way to draw unmodified block entity
 * and entity renderers into a private framebuffer under private lighting.
 *
 * <p>Render thread only.
 */
public final class RenderHooks {
    /** When set, {@code Minecraft.getMainRenderTarget()} returns this and fabulous transparency targets are bypassed. */
    public static @Nullable RenderTarget targetOverride;
    /** When not -1, this GL texture replaces the world lightmap. */
    public static int lightmapOverride = -1;

    private RenderHooks() {
    }
}
