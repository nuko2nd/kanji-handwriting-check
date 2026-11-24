package kanji.handwriting.check.model;

import java.util.Collection;

/**
 * An axis-aligned rectangle. Like {@link Point}, the owning object states the coordinate space;
 * the origin is top-left and y grows downward, so {@code minY} is the top edge.
 *
 * @param minX left edge
 * @param minY top edge
 * @param maxX right edge
 * @param maxY bottom edge
 */
public record Bounds(double minX, double minY, double maxX, double maxY) {
    /**
     * Width of the rectangle.
     *
     * @return {@code maxX - minX}
     */
    public double width() { return maxX - minX; }

    /**
     * Height of the rectangle.
     *
     * @return {@code maxY - minY}
     */
    public double height() { return maxY - minY; }

    /**
     * Midpoint of the rectangle.
     *
     * @return the point halfway between the edges on both axes
     */
    public Point center() { return new Point((minX + maxX) / 2, (minY + maxY) / 2); }

    /**
     * Computes the bounding box of a set of points.
     *
     * @param points at least one point
     * @return the smallest rectangle containing every point
     * @throws IllegalArgumentException if {@code points} is empty
     */
    public static Bounds of(Collection<Point> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("no points");
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Point p : points) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return new Bounds(minX, minY, maxX, maxY);
    }
}
