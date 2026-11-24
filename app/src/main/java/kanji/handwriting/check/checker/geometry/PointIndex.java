package kanji.handwriting.check.checker.geometry;

import java.util.ArrayList;
import java.util.List;
import kanji.handwriting.check.model.Point;

/**
 * Nearest-neighbour lookups over a fixed set of points, using a uniform grid of buckets. This
 * plays the role of a k-d tree: queries only look at buckets near the query point.
 */
public final class PointIndex {
    private final List<Point> points;
    private final double cell;
    private final int minCx, minCy, cols, rows;
    private final int[][] buckets;

    /**
     * Builds the index.
     *
     * @param points the points to index (may be empty)
     * @param cellSize bucket side; about the typical query radius works well
     */
    public PointIndex(List<Point> points, double cellSize) {
        this.points = List.copyOf(points);
        this.cell = cellSize;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (Point p : points) {
            x0 = Math.min(x0, cx(p.x()));
            y0 = Math.min(y0, cx(p.y()));
            x1 = Math.max(x1, cx(p.x()));
            y1 = Math.max(y1, cx(p.y()));
        }
        if (points.isEmpty()) {
            x0 = y0 = 0;
            x1 = y1 = 0;
        }
        minCx = x0;
        minCy = y0;
        cols = x1 - x0 + 1;
        rows = y1 - y0 + 1;
        List<List<Integer>> tmp = new ArrayList<>(cols * rows);
        for (int i = 0; i < cols * rows; i++) {
            tmp.add(new ArrayList<>());
        }
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            tmp.get((cx(p.y()) - minCy) * cols + (cx(p.x()) - minCx)).add(i);
        }
        buckets = new int[cols * rows][];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = tmp.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
    }

    private int cx(double v) {
        return (int) Math.floor(v / cell);
    }

    /**
     * The indexed points.
     *
     * @return the points, in the order given to the constructor
     */
    public List<Point> points() {
        return points;
    }

    /**
     * Finds the closest indexed point within a radius.
     *
     * @param q query point
     * @param maxDistance search radius
     * @return index of the nearest point, or -1 if none is within {@code maxDistance}
     */
    public int nearest(Point q, double maxDistance) {
        if (points.isEmpty()) {
            return -1;
        }
        int r = (int) Math.ceil(maxDistance / cell);
        int qx = cx(q.x()), qy = cx(q.y());
        int best = -1;
        double bestD2 = maxDistance * maxDistance;
        for (int y = Math.max(qy - r, minCy); y <= Math.min(qy + r, minCy + rows - 1); y++) {
            for (int x = Math.max(qx - r, minCx); x <= Math.min(qx + r, minCx + cols - 1); x++) {
                for (int i : buckets[(y - minCy) * cols + (x - minCx)]) {
                    Point p = points.get(i);
                    double dx = p.x() - q.x(), dy = p.y() - q.y(), d2 = dx * dx + dy * dy;
                    if (d2 <= bestD2) {
                        bestD2 = d2;
                        best = i;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Whether any indexed point lies within a radius.
     *
     * @param q query point
     * @param radius search radius
     * @return true if some point is within {@code radius} of {@code q}
     */
    public boolean anyWithin(Point q, double radius) {
        return nearest(q, radius) >= 0;
    }

    /**
     * Euclidean distance between two points.
     *
     * @param a first point
     * @param b second point
     * @return the distance
     */
    public static double distance(Point a, Point b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }
}
