package kanji.handwriting.check.checker.image;

import java.util.List;
import kanji.handwriting.check.model.Point;

/**
 * The user's drawing after preprocessing. Pixel coordinates are pixel centres; reference-space
 * coordinates are pixel coordinates × {@code unitsPerPixel}, which maps the canvas onto the
 * 109 × 109 reference cell (the initial alignment).
 *
 * @param width image width in pixels
 * @param height image height in pixels
 * @param unitsPerPixel {@code 109 / canvasSize}
 * @param ink binary ink mask, indexed {@code [y][x]}
 * @param skeleton one-pixel-wide centre lines of the ink, indexed {@code [y][x]}
 * @param skeletonPoints skeleton pixels in reference space
 * @param skeletonPixels the same skeleton pixels as {@code {x, y}} pixel coordinates, in the same
 *     order as {@code skeletonPoints}
 * @param inkPoints every ink pixel in reference space
 * @param inkPixels the same ink pixels as {@code {x, y}}, in the same order as {@code inkPoints}
 */
public record InkImage(
        int width,
        int height,
        double unitsPerPixel,
        boolean[][] ink,
        boolean[][] skeleton,
        List<Point> skeletonPoints,
        List<int[]> skeletonPixels,
        List<Point> inkPoints,
        List<int[]> inkPixels) {}
