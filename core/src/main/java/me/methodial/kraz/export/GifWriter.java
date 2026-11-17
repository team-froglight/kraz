package me.methodial.kraz.export;

import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Streams frames into a looping animated GIF using the JDK's built-in encoder. */
final class GifWriter implements Closeable {
    private final ImageWriter writer;
    private final ImageOutputStream stream;
    private final ImageWriteParam params;
    private final IIOMetadata metadata;

    /** @param delayCentiseconds time each frame is shown, in 1/100 s */
    GifWriter(Path path, int delayCentiseconds) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersBySuffix("gif");
        if (!writers.hasNext()) {
            throw new IOException("This Java runtime has no GIF encoder");
        }
        writer = writers.next();
        params = writer.getDefaultWriteParam();
        metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB), params);
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

        IIOMetadataNode control = child(root, "GraphicControlExtension");
        // Frames have transparent backgrounds, so each one must be cleared before the next is drawn.
        control.setAttribute("disposalMethod", "restoreToBackgroundColor");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", Integer.toString(delayCentiseconds));
        control.setAttribute("transparentColorIndex", "0");

        IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
        loop.setAttribute("applicationID", "NETSCAPE");
        loop.setAttribute("authenticationCode", "2.0");
        loop.setUserObject(new byte[]{1, 0, 0}); // loop forever
        child(root, "ApplicationExtensions").appendChild(loop);
        metadata.setFromTree(format, root);

        Files.deleteIfExists(path);
        stream = ImageIO.createImageOutputStream(path.toFile());
        if (stream == null) {
            throw new IOException("Cannot open " + path + " for writing");
        }
        writer.setOutput(stream);
        writer.prepareWriteSequence(null);
    }

    void add(BufferedImage frame) throws IOException {
        writer.writeToSequence(new IIOImage(frame, null, metadata), params);
    }

    @Override
    public void close() throws IOException {
        try {
            writer.endWriteSequence();
        } finally {
            writer.dispose();
            stream.close();
        }
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    /** Converts a straight-alpha image, snapping alpha to on/off since GIF has no partial transparency. */
    static BufferedImage toBufferedImage(NativeImage image) {
        int width = image.getWidth(), height = image.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int abgr = image.getPixelRGBA(x, y);
                row[x] = (abgr >>> 24) < 128 ? 0 : 0xFF000000 | (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16) & 0xFF;
            }
            result.setRGB(0, y, width, 1, row, 0, width);
        }
        return result;
    }
}
