package me.methodial.kraz.selftest;

import com.mojang.blaze3d.platform.NativeImage;

/** Pixel statistics used to assert on rendered output. Pixels are ABGR ints as NativeImage stores them. */
final class Images {
    private Images() {
    }

    static int alpha(int abgr) {
        return abgr >>> 24;
    }

    static int red(int abgr) {
        return abgr & 0xFF;
    }

    static int green(int abgr) {
        return (abgr >> 8) & 0xFF;
    }

    static int blue(int abgr) {
        return (abgr >> 16) & 0xFF;
    }

    /** Fraction of pixels that are not fully transparent. */
    static double coverage(NativeImage image) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alpha(image.getPixelRGBA(x, y)) != 0) {
                    n++;
                }
            }
        }
        return (double) n / (image.getWidth() * image.getHeight());
    }

    /** Number of pixels with alpha strictly between 0 and 255: antialiased edges or translucent content. */
    static int partialAlpha(NativeImage image) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int a = alpha(image.getPixelRGBA(x, y));
                if (a != 0 && a != 255) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Number of pixels differing by more than {@code tolerance} in any channel; -1 if sizes differ. */
    static int differing(NativeImage a, NativeImage b, int tolerance) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return -1;
        }
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getPixelRGBA(x, y), q = b.getPixelRGBA(x, y);
                if (Math.abs(alpha(p) - alpha(q)) > tolerance || Math.abs(red(p) - red(q)) > tolerance
                        || Math.abs(green(p) - green(q)) > tolerance || Math.abs(blue(p) - blue(q)) > tolerance) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Number of distinct opaque colours, capped; a quick "is this a real textured render" signal. */
    static int distinctColors(NativeImage image, int cap) {
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int y = 0; y < image.getHeight() && seen.size() < cap; y++) {
            for (int x = 0; x < image.getWidth() && seen.size() < cap; x++) {
                int p = image.getPixelRGBA(x, y);
                if (alpha(p) == 255) {
                    seen.add(p);
                }
            }
        }
        return seen.size();
    }

    static String describe(int abgr) {
        return "rgba(" + red(abgr) + "," + green(abgr) + "," + blue(abgr) + "," + alpha(abgr) + ")";
    }
}
