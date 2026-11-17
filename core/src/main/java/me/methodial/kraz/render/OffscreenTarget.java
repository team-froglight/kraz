package me.methodial.kraz.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import me.methodial.kraz.render.post.PostEffect;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.List;

/**
 * A private framebuffer with optional multisampling and supersampling.
 *
 * <pre>
 *   multisampled renderbuffers ──resolve──▶ texture (supersampled size) ──halve…──▶ output texture ──▶ post effects
 * </pre>
 *
 * Between {@link #begin} and {@link #end} the target stands in for Minecraft's main render target, so every vanilla
 * render type draws into it. The colour output uses premultiplied alpha.
 */
public final class OffscreenTarget implements AutoCloseable {
    private int width, height;
    private int samples, supersample;

    private int msFramebuffer = -1, msColor = -1, msDepth = -1;
    private @Nullable RenderTarget msTarget;
    /** Full supersampled size; has its own depth buffer only when multisampling is off. */
    private @Nullable TextureTarget resolve;
    /** Intermediate (for 4× supersampling) and final downsample steps; empty when supersampling is off. */
    private TextureTarget[] chain = new TextureTarget[0];
    private @Nullable TextureTarget scratch;
    private @Nullable RenderTarget output;

    private @Nullable RenderTarget previousOverride;
    private boolean drawing;

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** (Re)allocates buffers if the requested configuration differs from the current one. */
    public void configure(int width, int height, RenderSettings settings) {
        RenderSystem.assertOnRenderThread();
        width = Math.max(1, width);
        height = Math.max(1, height);
        int maxSamples = GL11.glGetInteger(GL30.GL_MAX_SAMPLES);
        int samples = Math.min(settings.msaaSamples(), maxSamples);
        if (samples < 2) {
            samples = 0;
        }
        int supersample = settings.supersample();
        int maxSize = RenderSystem.maxSupportedTextureSize();
        while (supersample > 1 && (width * supersample > maxSize || height * supersample > maxSize)) {
            supersample /= 2;
        }
        if (resolve != null && width == this.width && height == this.height && samples == this.samples && supersample == this.supersample) {
            return;
        }
        destroy();
        this.width = width;
        this.height = height;
        this.samples = samples;
        this.supersample = supersample;

        int bigWidth = width * supersample, bigHeight = height * supersample;
        resolve = newTexture(bigWidth, bigHeight, samples == 0);
        if (samples > 0) {
            createMultisample(bigWidth, bigHeight, samples);
        }
        int steps = supersample == 4 ? 2 : supersample == 2 ? 1 : 0;
        chain = new TextureTarget[steps];
        for (int i = 0; i < steps; i++) {
            int divisor = 2 << i;
            chain[i] = newTexture(bigWidth / divisor, bigHeight / divisor, false);
        }
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    private static TextureTarget newTexture(int width, int height, boolean depth) {
        TextureTarget target = new TextureTarget(width, height, depth, Minecraft.ON_OSX);
        target.setClearColor(0, 0, 0, 0);
        target.setFilterMode(GL11.GL_LINEAR);
        return target;
    }

    private void createMultisample(int width, int height, int samples) {
        msFramebuffer = GlStateManager.glGenFramebuffers();
        msColor = GL30.glGenRenderbuffers();
        msDepth = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, msColor);
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL11.GL_RGBA8, width, height);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, msDepth);
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL30.GL_DEPTH_COMPONENT24, width, height);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, msFramebuffer);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, msColor);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, msDepth);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Kraz: multisampled framebuffer incomplete (0x" + Integer.toHexString(status) + ")");
        }
        // A bare handle so vanilla code that binds "the main render target" lands in the multisampled framebuffer.
        RenderTarget handle = new RenderTarget(true) {
        };
        handle.frameBufferId = msFramebuffer;
        handle.width = handle.viewWidth = width;
        handle.height = handle.viewHeight = height;
        msTarget = handle;
    }

    /** Starts drawing: binds and clears the target and redirects Minecraft's main render target to it. */
    public void begin(int backgroundArgb) {
        RenderSystem.assertOnRenderThread();
        if (resolve == null) {
            throw new IllegalStateException("configure() must be called before begin()");
        }
        if (drawing) {
            throw new IllegalStateException("begin() called twice");
        }
        drawing = true;
        RenderTarget drawTarget = msTarget != null ? msTarget : resolve;
        previousOverride = RenderHooks.targetOverride;
        RenderHooks.targetOverride = drawTarget;
        drawTarget.bindWrite(true);
        float a = (backgroundArgb >>> 24) / 255.0f;
        // Premultiplied clear colour, matching what alpha blending produces for everything drawn on top.
        RenderSystem.clearColor(
                ((backgroundArgb >> 16) & 0xFF) / 255.0f * a,
                ((backgroundArgb >> 8) & 0xFF) / 255.0f * a,
                (backgroundArgb & 0xFF) / 255.0f * a, a);
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    /** Finishes drawing: resolves, downsamples, runs post effects and rebinds the real main render target. */
    public void end(List<PostEffect> effects, float timeSeconds) {
        RenderSystem.assertOnRenderThread();
        if (!drawing) {
            throw new IllegalStateException("end() without begin()");
        }
        drawing = false;
        RenderHooks.targetOverride = previousOverride;
        previousOverride = null;

        TextureTarget resolved = resolve;
        if (msTarget != null) {
            blit(msFramebuffer, resolved.frameBufferId, resolved.width, resolved.height, resolved.width, resolved.height, GL11.GL_NEAREST);
        }
        RenderTarget current = resolved;
        for (TextureTarget step : chain) {
            blit(current.frameBufferId, step.frameBufferId, current.width, current.height, step.width, step.height, GL11.GL_LINEAR);
            current = step;
        }
        if (!effects.isEmpty()) {
            if (scratch == null) {
                scratch = newTexture(width, height, false);
            }
            RenderTarget spare = scratch;
            for (PostEffect effect : effects) {
                if (effect.apply(current, spare, timeSeconds)) {
                    RenderTarget swap = current;
                    current = spare;
                    // "current" is always output-sized at this point, so the two buffers simply swap roles.
                    spare = swap;
                }
            }
        }
        output = current;
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    private static void blit(int from, int to, int fromWidth, int fromHeight, int toWidth, int toHeight, int filter) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to);
        GlStateManager._glBlitFrameBuffer(0, 0, fromWidth, fromHeight, 0, 0, toWidth, toHeight, GL11.GL_COLOR_BUFFER_BIT, filter);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** The finished image of the last {@link #end}: an output-sized colour texture with premultiplied alpha. */
    public RenderTarget output() {
        if (output == null) {
            throw new IllegalStateException("Nothing has been rendered yet");
        }
        return output;
    }

    public boolean hasOutput() {
        return output != null;
    }

    private void destroy() {
        if (msFramebuffer != -1) {
            GlStateManager._glDeleteFramebuffers(msFramebuffer);
            GL30.glDeleteRenderbuffers(msColor);
            GL30.glDeleteRenderbuffers(msDepth);
            msFramebuffer = msColor = msDepth = -1;
            msTarget = null;
        }
        if (resolve != null) {
            resolve.destroyBuffers();
            resolve = null;
        }
        for (TextureTarget step : chain) {
            step.destroyBuffers();
        }
        chain = new TextureTarget[0];
        if (scratch != null) {
            scratch.destroyBuffers();
            scratch = null;
        }
        output = null;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        destroy();
    }
}
