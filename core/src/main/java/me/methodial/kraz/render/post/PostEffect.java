package me.methodial.kraz.render.post;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * A full-screen pass over a finished scene image. Effects are chained through {@code RenderSettings.effects()}.
 *
 * <p>Images use premultiplied alpha and may have transparent regions; a well-behaved effect preserves both.
 */
@FunctionalInterface
public interface PostEffect {
    /**
     * Reads {@code in}'s colour texture and writes the processed image to {@code out}. Both have the same size.
     *
     * @return {@code false} if the effect did nothing (for example because its shader is unavailable), in which
     *         case {@code in} stays the current image
     */
    boolean apply(RenderTarget in, RenderTarget out, float timeSeconds);
}
