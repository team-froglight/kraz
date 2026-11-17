package me.methodial.kraz.render;

import me.methodial.kraz.render.post.PostEffect;

import java.util.List;

/**
 * Quality settings for offscreen scene rendering.
 *
 * @param msaaSamples  multisample antialiasing sample count; 0 or 1 disables it. Clamped to what the GPU supports.
 * @param supersample  render at this multiple of the output size and filter down: 1, 2 or 4. Unlike MSAA this also
 *                     smooths texture shimmer and cutout edges, at a proportional fill-rate cost.
 * @param effects      post-processing passes applied in order to the resolved image
 */
public record RenderSettings(int msaaSamples, int supersample, List<PostEffect> effects) {
    /** 4× MSAA: clean edges at very low cost. The default for on-screen scenes. */
    public static final RenderSettings DEFAULT = new RenderSettings(4, 1, List.of());
    /** No antialiasing at all. */
    public static final RenderSettings FAST = new RenderSettings(0, 1, List.of());
    /** 8× MSAA on a 2× supersampled image; meant for exported stills and animations. */
    public static final RenderSettings HIGH_QUALITY = new RenderSettings(8, 2, List.of());

    public RenderSettings {
        msaaSamples = Math.max(0, msaaSamples);
        supersample = supersample >= 4 ? 4 : supersample >= 2 ? 2 : 1;
        effects = List.copyOf(effects);
    }

    public RenderSettings withMsaa(int samples) {
        return new RenderSettings(samples, supersample, effects);
    }

    public RenderSettings withSupersample(int factor) {
        return new RenderSettings(msaaSamples, factor, effects);
    }

    public RenderSettings withEffects(PostEffect... effects) {
        return new RenderSettings(msaaSamples, supersample, List.of(effects));
    }
}
