package me.methodial.kraz.export;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.render.SceneCanvas;
import me.methodial.kraz.scene.Scene;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Renders scenes to files. Rendering happens immediately on the render thread (it has to: it needs the GL
 * context); pixel conversion, compression and disk I/O happen on a background thread, and the returned future
 * completes when the file is on disk.
 */
public final class SceneExporter {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Kraz-Export");
        thread.setDaemon(true);
        return thread;
    });
    /** Bounds how many rendered-but-unwritten frames may sit in memory during an animation export. */
    private static final int MAX_QUEUED_FRAMES = 8;

    private SceneExporter() {
    }

    /** {@code <game dir>/kraz/exports/<name>.<extension>}, numbered if the file already exists. */
    public static Path exportPath(String name, String extension) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("kraz").resolve("exports");
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        Path path = dir.resolve(safe + "." + extension);
        for (int i = 1; Files.exists(path); i++) {
            path = dir.resolve(safe + "_" + i + "." + extension);
        }
        return path;
    }

    /** Renders the scene in its current state. The caller owns the image; alpha is straight (not premultiplied). */
    public static NativeImage renderImage(Scene scene, int width, int height, RenderSettings settings) {
        try (SceneCanvas canvas = new SceneCanvas(scene).setSettings(settings)) {
            NativeImage image = canvas.capture(width, height);
            SceneCanvas.unpremultiply(image);
            return image;
        }
    }

    /** Saves the scene in its current state as a PNG with transparency. */
    public static CompletableFuture<Path> exportPng(Scene scene, Path path, int width, int height, RenderSettings settings) {
        RenderSystem.assertOnRenderThread();
        NativeImage image;
        try (SceneCanvas canvas = new SceneCanvas(scene).setSettings(settings)) {
            image = canvas.capture(width, height);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
        return CompletableFuture.supplyAsync(() -> {
            try (image) {
                SceneCanvas.unpremultiply(image);
                Files.createDirectories(path.toAbsolutePath().getParent());
                image.writeToFile(path);
                return path;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, IO);
    }

    /**
     * Plays the scene's timeline from start to end and saves every frame as {@code frame_0000.png}, … in
     * {@code directory}. The timeline is restored to where it was afterwards.
     */
    public static CompletableFuture<Path> exportPngSequence(Scene scene, Path directory, int width, int height, int fps, RenderSettings settings) {
        return exportFrames(scene, width, height, fps, settings, new FrameSink() {
            @Override
            public void begin() throws IOException {
                Files.createDirectories(directory);
            }

            @Override
            public void frame(int index, NativeImage image) throws IOException {
                image.writeToFile(directory.resolve(String.format("frame_%04d.png", index)));
            }

            @Override
            public Path finish() {
                return directory;
            }
        });
    }

    /**
     * Plays the scene's timeline from start to end and saves it as a looping animated GIF. GIF has no partial
     * transparency: pixels are either opaque or fully transparent.
     */
    public static CompletableFuture<Path> exportGif(Scene scene, Path path, int width, int height, int fps, RenderSettings settings) {
        return exportFrames(scene, width, height, fps, settings, new FrameSink() {
            private GifWriter writer;

            @Override
            public void begin() throws IOException {
                Files.createDirectories(path.toAbsolutePath().getParent());
                writer = new GifWriter(path, Math.max(1, Math.round(100.0f / fps)));
            }

            @Override
            public void frame(int index, NativeImage image) throws IOException {
                writer.add(GifWriter.toBufferedImage(image));
            }

            @Override
            public Path finish() throws IOException {
                writer.close();
                return path;
            }
        });
    }

    private interface FrameSink {
        void begin() throws IOException;

        /** Called on the export thread with a straight-alpha image, which is closed afterwards. */
        void frame(int index, NativeImage image) throws IOException;

        Path finish() throws IOException;
    }

    private static CompletableFuture<Path> exportFrames(Scene scene, int width, int height, int fps, RenderSettings settings, FrameSink sink) {
        RenderSystem.assertOnRenderThread();
        Timeline timeline = scene.timeline();
        float savedTime = timeline.time();
        float length = timeline.length();
        fps = Math.clamp(fps, 1, 120);
        int frameCount = Math.max(1, (int) Math.ceil(length / 20.0f * fps));
        Semaphore queue = new Semaphore(MAX_QUEUED_FRAMES);
        CompletableFuture<Void> chain = CompletableFuture.runAsync(() -> {
            try {
                sink.begin();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, IO);
        try (SceneCanvas canvas = new SceneCanvas(scene).setSettings(settings)) {
            for (int i = 0; i < frameCount; i++) {
                if (chain.isCompletedExceptionally()) {
                    break;
                }
                timeline.seek(i * 20.0f / fps);
                NativeImage image = canvas.capture(width, height);
                queue.acquireUninterruptibly();
                int index = i;
                chain = chain.thenRunAsync(() -> {
                    try (image) {
                        SceneCanvas.unpremultiply(image);
                        sink.frame(index, image);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        queue.release();
                    }
                }, IO).whenComplete((ok, error) -> {
                    if (error != null) {
                        // Stage skipped because an earlier one failed: still free the frame and its queue slot.
                        image.close();
                        queue.release();
                    }
                });
            }
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        } finally {
            timeline.seek(savedTime);
        }
        return chain.thenApplyAsync(ok -> {
            try {
                return sink.finish();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, IO);
    }
}
