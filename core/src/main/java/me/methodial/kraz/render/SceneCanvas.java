package me.methodial.kraz.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.methodial.kraz.KrazClient;
import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.scene.Scene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.Objects;

/**
 * Renders a {@link Scene} through its own camera into a private antialiased framebuffer, and puts the result
 * wherever you need it: a rectangle in a GUI ({@link #draw}), a texture ({@link #render}) or an image
 * ({@link #capture}).
 *
 * <p>A canvas owns GPU resources: {@link #close()} it when done (for a screen, in {@code removed()}).
 * Render thread only.
 */
public final class SceneCanvas implements AutoCloseable {
    /** When a canvas re-renders its scene. */
    public enum Redraw {
        /** Every frame. Always correct, including animated textures such as water. */
        ALWAYS,
        /**
         * Only when the scene, camera, size or settings changed, or the scene has block entities or elements.
         * Otherwise the cached image is reused, which makes static scenes (icons, thumbnails) essentially free.
         * Animated block textures do not trigger a redraw in this mode.
         */
        WHEN_CHANGED
    }

    private final Scene scene;
    private final SceneRenderer renderer;
    private final OffscreenTarget target = new OffscreenTarget();
    private RenderSettings settings = RenderSettings.DEFAULT;
    private Redraw redraw = Redraw.ALWAYS;

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private long lastSignature = Long.MIN_VALUE;
    private boolean closed;
    private long framesRendered;

    public SceneCanvas(Scene scene) {
        this.scene = scene;
        this.renderer = new SceneRenderer(scene);
    }

    public Scene scene() {
        return scene;
    }

    public RenderSettings settings() {
        return settings;
    }

    public SceneCanvas setSettings(RenderSettings settings) {
        this.settings = Objects.requireNonNull(settings);
        return this;
    }

    public SceneCanvas setRedraw(Redraw redraw) {
        this.redraw = redraw;
        return this;
    }

    /** How many times the scene was actually drawn, as opposed to served from the cached image. */
    public long framesRendered() {
        return framesRendered;
    }

    /** Whether geometry is still streaming in from the background meshing threads. */
    public boolean busy() {
        return renderer.busy();
    }

    /**
     * Renders the scene at the given pixel size and returns the finished image as a render target whose colour
     * texture uses premultiplied alpha. Afterwards Minecraft's main render target is bound again.
     *
     * @param wait block until all scene geometry is built; use for screenshots, leave off for interactive views
     */
    public RenderTarget render(int pixelWidth, int pixelHeight, float partialTick, boolean wait) {
        RenderSystem.assertOnRenderThread();
        if (closed) {
            throw new IllegalStateException("Canvas is closed");
        }
        pixelWidth = Math.max(1, pixelWidth);
        pixelHeight = Math.max(1, pixelHeight);
        SceneCamera camera = scene.camera();
        camera.position(cameraPosition);
        renderer.prepare(cameraPosition, wait);

        long signature = signature(pixelWidth, pixelHeight);
        boolean reuse = redraw == Redraw.WHEN_CHANGED && target.hasOutput() && signature == lastSignature
                && !renderer.meshesChanged() && !renderer.hasDynamicContent();
        if (reuse) {
            return target.output();
        }
        lastSignature = signature;
        framesRendered++;

        // Whatever is using the GL context around us (usually GUI rendering) may have a scissor box active.
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = new int[4];
        if (scissor) {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            RenderSystem.disableScissor();
        }
        target.configure(pixelWidth, pixelHeight, settings);
        camera.viewMatrix(view);
        camera.projectionMatrix((float) pixelWidth / pixelHeight, projection);
        target.begin(scene.background());
        try {
            renderer.draw(view, projection, scene.timeline().time(), partialTick, true);
        } finally {
            target.end(settings.effects(), scene.timeline().time() / 20.0f);
            Lighting.setupFor3DItems();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableCull();
            if (scissor) {
                RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            }
        }
        return target.output();
    }

    private long signature(int width, int height) {
        long s = scene.version();
        s = s * 31 + Float.floatToIntBits(scene.timeline().time());
        s = s * 31 + width;
        s = s * 31 + height;
        s = s * 31 + settings.hashCode();
        s = s * 31 + KrazClient.resourceGeneration();
        return s;
    }

    /**
     * Draws the scene into a rectangle of a GUI, at native pixel resolution regardless of GUI scale.
     * Coordinates are in GUI units and respect the current pose of {@code graphics}.
     */
    public void draw(GuiGraphics graphics, int x, int y, int width, int height, float partialTick) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        draw(graphics, x, y, width, height, (int) Math.round(width * guiScale), (int) Math.round(height * guiScale), partialTick);
    }

    /**
     * As {@link #draw(GuiGraphics, int, int, int, int, float)}, with the render resolution chosen by the caller:
     * for rectangles that are scaled or rotated by the pose, where GUI units no longer say how many pixels the
     * picture covers.
     */
    public void draw(GuiGraphics graphics, float x, float y, float width, float height, int pixelWidth, int pixelHeight, float partialTick) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.flush();
        RenderTarget image = render(pixelWidth, pixelHeight, partialTick, false);
        blit(image, graphics.pose().last().pose(), x, y, width, height);
    }

    /** Draws a finished image as a quad, compositing its premultiplied alpha correctly. */
    public static void blit(RenderTarget image, Matrix4f pose, float x, float y, float width, float height) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, image.getColorTextureId());
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // Framebuffer textures have their origin at the bottom left, GUI space at the top left.
        builder.addVertex(pose, x, y + height, 0).setUv(0, 0);
        builder.addVertex(pose, x + width, y + height, 0).setUv(1, 0);
        builder.addVertex(pose, x + width, y, 0).setUv(1, 1);
        builder.addVertex(pose, x, y, 0).setUv(0, 1);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.defaultBlendFunc();
    }

    /**
     * Renders the scene to an image, waiting for all geometry first. The image has the usual top-left origin and
     * premultiplied alpha; run {@link #unpremultiply} on it (from any thread) before saving it to a file.
     * The caller owns the image and must close it.
     */
    public NativeImage capture(int pixelWidth, int pixelHeight) {
        lastSignature = Long.MIN_VALUE;
        RenderTarget image = render(pixelWidth, pixelHeight, 0.0f, true);
        NativeImage pixels = new NativeImage(image.width, image.height, false);
        RenderSystem.bindTexture(image.getColorTextureId());
        pixels.downloadTexture(0, false);
        pixels.flipY();
        return pixels;
    }

    /** Converts an image from premultiplied to straight alpha in place. Safe to call from any thread. */
    public static void unpremultiply(NativeImage image) {
        int width = image.getWidth(), height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int abgr = image.getPixelRGBA(x, y);
                int a = abgr >>> 24;
                if (a == 0 || a == 255) {
                    continue;
                }
                int r = Math.min(255, (abgr & 0xFF) * 255 / a);
                int g = Math.min(255, ((abgr >> 8) & 0xFF) * 255 / a);
                int b = Math.min(255, ((abgr >> 16) & 0xFF) * 255 / a);
                image.setPixelRGBA(x, y, a << 24 | b << 16 | g << 8 | r);
            }
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            renderer.close();
            target.close();
        }
    }
}
