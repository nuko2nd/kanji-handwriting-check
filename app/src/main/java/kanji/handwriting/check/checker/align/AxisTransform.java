package kanji.handwriting.check.checker.align;

import kanji.handwriting.check.model.Point;

/**
 * Separate horizontal and vertical scaling about a centre, then a shift:
 * {@code p' = (cx + sx·(x − cx) + tx, cy + sy·(y − cy) + ty)}. Unlike a similarity transform it can
 * squash or stretch one direction more than the other, as handwriting often does to a part of a
 * character (a flat 五 above a tall 口).
 *
 * @param sx horizontal scale
 * @param sy vertical scale
 * @param tx shift along x
 * @param ty shift along y
 * @param cx centre x
 * @param cy centre y
 */
public record AxisTransform(double sx, double sy, double tx, double ty, double cx, double cy) implements PointMap {
    @Override
    public Point apply(Point p) {
        return new Point(cx + sx * (p.x() - cx) + tx, cy + sy * (p.y() - cy) + ty);
    }

    /**
     * The size change that counts for scoring: whichever axis changed more (in log terms).
     *
     * @return {@code sx} or {@code sy}
     */
    public double dominantScale() {
        return Math.abs(Math.log(sx)) >= Math.abs(Math.log(sy)) ? sx : sy;
    }
}
