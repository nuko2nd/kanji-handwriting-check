package kanji.handwriting.check.checker.align;

import java.util.List;
import kanji.handwriting.check.model.Point;

/**
 * Scale, rotation and translation about a fixed centre:
 * {@code p' = c + scale · R(rotation) · (p − c) + (tx, ty)}. Using the cell centre as {@code c}
 * keeps the three parameters independent, so each can be bounded on its own.
 *
 * @param scale uniform scale factor
 * @param rotation rotation in radians (positive is clockwise on screen, since y points down)
 * @param tx shift along x, in reference units
 * @param ty shift along y, in reference units
 * @param cx centre x
 * @param cy centre y
 */
public record SimilarityTransform(double scale, double rotation, double tx, double ty, double cx, double cy)
        implements PointMap {

    /**
     * The identity about a centre.
     *
     * @param cx centre x
     * @param cy centre y
     * @return a transform that leaves points unchanged
     */
    public static SimilarityTransform identity(double cx, double cy) {
        return new SimilarityTransform(1, 0, 0, 0, cx, cy);
    }

    /**
     * Applies the transform.
     *
     * @param p a point
     * @return the transformed point
     */
    @Override
    public Point apply(Point p) {
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        double dx = p.x() - cx, dy = p.y() - cy;
        return new Point(cx + scale * (cos * dx - sin * dy) + tx, cy + scale * (sin * dx + cos * dy) + ty);
    }

    /**
     * Applies the transform to many points.
     *
     * @param points the points
     * @return the transformed points, in the same order
     */
    @Override
    public List<Point> apply(List<Point> points) {
        return points.stream().map(this::apply).toList();
    }

    /**
     * This transform applied after another with the same centre.
     *
     * @param first the transform applied first
     * @return {@code p -> this.apply(first.apply(p))} as one transform
     * @throws IllegalArgumentException if the centres differ
     */
    public SimilarityTransform after(SimilarityTransform first) {
        if (first.cx != cx || first.cy != cy) {
            throw new IllegalArgumentException("Different centres");
        }
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        return new SimilarityTransform(scale * first.scale, rotation + first.rotation,
                scale * (cos * first.tx - sin * first.ty) + tx, scale * (sin * first.tx + cos * first.ty) + ty, cx, cy);
    }

    /**
     * Rotation in degrees.
     *
     * @return {@code rotation} converted to degrees
     */
    public double rotationDegrees() {
        return Math.toDegrees(rotation);
    }
}
