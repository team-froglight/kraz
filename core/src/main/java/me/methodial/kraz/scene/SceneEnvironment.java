package me.methodial.kraz.scene;

/**
 * Immutable description of how a scene is lit and tinted. Being immutable, it can be shared freely with the
 * background meshing threads.
 *
 * @param lightMode     how block and sky light are derived
 * @param skyBrightness 0 (midnight) .. 1 (noon); drives the scene's private lightmap
 * @param gamma         brightness option equivalent, 0 .. 1
 * @param shade         whether directional face shading is applied to block faces
 * @param grassColor    RGB tint used for grass
 * @param foliageColor  RGB tint used for foliage
 * @param waterColor    RGB tint used for water
 */
public record SceneEnvironment(
        LightMode lightMode,
        float skyBrightness,
        float gamma,
        boolean shade,
        int grassColor,
        int foliageColor,
        int waterColor
) {
    public static final SceneEnvironment DEFAULT =
            new SceneEnvironment(LightMode.FULL_BRIGHT, 1.0f, 0.5f, true, 0x91BD59, 0x77AB2F, 0x3F76E4);

    public enum LightMode {
        /** Every block receives full sky light. Cheapest, and what guide-style scenes usually want. */
        FULL_BRIGHT,
        /** Sky and block light are flood-filled through the scene, so interiors are dark and torches glow. */
        COMPUTED
    }

    public SceneEnvironment withLightMode(LightMode mode) {
        return new SceneEnvironment(mode, skyBrightness, gamma, shade, grassColor, foliageColor, waterColor);
    }

    public SceneEnvironment withSkyBrightness(float value) {
        return new SceneEnvironment(lightMode, Math.clamp(value, 0f, 1f), gamma, shade, grassColor, foliageColor, waterColor);
    }

    public SceneEnvironment withGamma(float value) {
        return new SceneEnvironment(lightMode, skyBrightness, Math.clamp(value, 0f, 1f), shade, grassColor, foliageColor, waterColor);
    }

    public SceneEnvironment withShade(boolean value) {
        return new SceneEnvironment(lightMode, skyBrightness, gamma, value, grassColor, foliageColor, waterColor);
    }

    public SceneEnvironment withTints(int grass, int foliage, int water) {
        return new SceneEnvironment(lightMode, skyBrightness, gamma, shade, grass, foliage, water);
    }
}
