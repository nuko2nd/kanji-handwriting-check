package kanji.handwriting.check.checker.image;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.model.Point;

/** Grayscale, fixed threshold, skeletonize, and map pixels into reference space. */
public final class InkPreprocessor {
    /** Side of the KanjiVG reference cell. */
    public static final double REFERENCE_SIZE = 109;

    /** Images wider than this are scaled down before processing (about 7 px per reference unit). */
    public static final int MAX_WORKING_SIZE = 800;

    private InkPreprocessor() {}

    private static BufferedImage downscale(BufferedImage src, double f) {
        int w = Math.max(1, (int) Math.round(src.getWidth() * f)), h = Math.max(1, (int) Math.round(src.getHeight() * f));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /**
     * Preprocesses a drawing.
     *
     * @param image the drawing: dark ink on a light background, on a square canvas
     * @param config uses {@code inkThreshold} and {@code canvasSize}
     * @return the ink mask, its skeleton, and their points in reference space
     */
    public static InkImage process(BufferedImage image, CheckerConfig config) {
        double canvasPx = config.canvasSize() > 0 ? config.canvasSize() : image.getWidth();
        // Large photos and tablet exports (e.g. 3600 px) carry far more detail than the 109-unit
        // reference needs, and every later step scales with the pixel count; work at most
        // MAX_WORKING_SIZE pixels across.
        if (image.getWidth() > MAX_WORKING_SIZE) {
            double f = MAX_WORKING_SIZE / (double) image.getWidth();
            image = downscale(image, f);
            canvasPx *= f;
        }
        int w = image.getWidth(), h = image.getHeight();
        double unitsPerPixel = REFERENCE_SIZE / canvasPx;

        boolean[][] ink = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xff, r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
                double grey = 0.299 * r + 0.587 * g + 0.114 * b;
                grey = 255 - (255 - grey) * a / 255.0; // transparent pixels count as white background
                ink[y][x] = grey < config.inkThreshold();
            }
        }
        boolean[][] skeleton = Skeletonizer.skeletonize(ink);

        List<Point> skelPts = new ArrayList<>(), inkPts = new ArrayList<>();
        List<int[]> skelPx = new ArrayList<>(), inkPx = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Point p = new Point((x + 0.5) * unitsPerPixel, (y + 0.5) * unitsPerPixel);
                if (ink[y][x]) {
                    inkPts.add(p);
                    inkPx.add(new int[] {x, y});
                }
                if (skeleton[y][x]) {
                    skelPts.add(p);
                    skelPx.add(new int[] {x, y});
                }
            }
        }
        return new InkImage(w, h, unitsPerPixel, ink, skeleton, List.copyOf(skelPts), List.copyOf(skelPx),
                List.copyOf(inkPts), List.copyOf(inkPx));
    }
}
