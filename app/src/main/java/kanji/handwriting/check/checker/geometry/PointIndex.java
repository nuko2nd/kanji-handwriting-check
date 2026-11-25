package kanji.handwriting.check.checker.geometry;

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
    /** Points grouped by bucket: bucket b holds slots {@code start[b] .. start[b + 1] - 1}. */
    private final int[] start;
    /** Per slot: the point's index in {@link #points}, and its coordinates (flat, for speed). */
    private final int[] order;
    private final double[] xs, ys;

    /**
     * Builds the index.
     *
     * @param points the points to index (may be empty)
     * @param cellSize bucket side; about the typical query radius works well
     */
    public PointIndex(List<Point> points, double cellSize) {
        this.points = List.copyOf(points);
        this.cell = cellSize;
        int n = this.points.size();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        int[] bx = new int[n], by = new int[n];
        for (int i = 0; i < n; i++) {
            Point p = this.points.get(i);
            bx[i] = cx(p.x());
            by[i] = cx(p.y());
            x0 = Math.min(x0, bx[i]);
            y0 = Math.min(y0, by[i]);
            x1 = Math.max(x1, bx[i]);
            y1 = Math.max(y1, by[i]);
        }
        if (n == 0) {
            x0 = y0 = 0;
            x1 = y1 = 0;
        }
        minCx = x0;
        minCy = y0;
        cols = x1 - x0 + 1;
        rows = y1 - y0 + 1;
        start = new int[cols * rows + 1];
        int[] bucketOf = new int[n];
        for (int i = 0; i < n; i++) {
            bucketOf[i] = (by[i] - minCy) * cols + (bx[i] - minCx);
            start[bucketOf[i] + 1]++;
        }
        for (int b = 0; b < cols * rows; b++) {
            start[b + 1] += start[b];
        }
        int[] fill = java.util.Arrays.copyOf(start, cols * rows);
        order = new int[n];
        xs = new double[n];
        ys = new double[n];
        for (int i = 0; i < n; i++) { // in index order, so each bucket lists its points by index
            int slot = fill[bucketOf[i]]++;
            order[slot] = i;
            xs[slot] = this.points.get(i).x();
            ys[slot] = this.points.get(i).y();
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
     * @return index of the nearest point (the lowest index among equally near ones), or -1 if none
     *     is within {@code maxDistance}
     */
    public int nearest(Point q, double maxDistance) {
        if (points.isEmpty()) {
            return -1;
        }
        double qxv = q.x(), qyv = q.y();
        int r = (int) Math.ceil(maxDistance / cell);
        int qx = cx(qxv), qy = cx(qyv);
        int maxCx = minCx + cols - 1, maxCy = minCy + rows - 1;
        int best = -1;
        double bestD2 = maxDistance * maxDistance;
        // Search rings of buckets outward from the query's bucket. Every point in ring k is at
        // least (k - 1)·cell away, so once the best distance is below that, no farther ring can do
        // better.
        for (int k = 0; k <= r; k++) {
            if (best >= 0 && k > 1 && bestD2 < (k - 1) * cell * ((k - 1) * cell)) {
                break;
            }
            int y0 = qy - k, y1 = qy + k, x0 = qx - k, x1 = qx + k;
            if (y0 < minCy && y1 > maxCy && x0 < minCx && x1 > maxCx) {
                break; // the ring lies wholly outside the grid, and so will every larger one
            }
            if (y1 < minCy || y0 > maxCy || x1 < minCx || x0 > maxCx) {
                continue; // the ring has not reached the grid yet
            }
            int ya = Math.max(y0, minCy), yb = Math.min(y1, maxCy), xa = Math.max(x0, minCx), xb = Math.min(x1, maxCx);
            for (int y = ya; y <= yb; y++) {
                boolean edgeRow = y == y0 || y == y1;
                int row = (y - minCy) * cols - minCx;
                for (int x = xa; x <= xb; x++) {
                    if (!edgeRow && x != x0 && x != x1) {
                        x = x1 - 1; // inside the ring: jump to its right edge
                        continue;
                    }
                    int b = row + x;
                    for (int slot = start[b], end = start[b + 1]; slot < end; slot++) {
                        double dx = xs[slot] - qxv, dy = ys[slot] - qyv, d2 = dx * dx + dy * dy;
                        if (d2 < bestD2 || (d2 == bestD2 && (best < 0 || order[slot] < best))) {
                            bestD2 = d2;
                            best = order[slot];
                        }
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
