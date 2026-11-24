package kanji.handwriting.check.checker.geometry;

import java.util.ArrayList;
import java.util.List;
import kanji.handwriting.check.model.Point;

/** Small helpers for polylines (lists of points joined by straight segments). */
public final class Polylines {
    private Polylines() {}

    /**
     * Total length of a polyline.
     *
     * @param line the polyline
     * @return the sum of its segment lengths
     */
    public static double length(List<Point> line) {
        double total = 0;
        for (int i = 1; i < line.size(); i++) {
            total += PointIndex.distance(line.get(i - 1), line.get(i));
        }
        return total;
    }

    /**
     * Resamples a polyline at equal arc-length spacing. Both endpoints are always included.
     *
     * @param line the polyline, at least one point
     * @param step target spacing between consecutive output points
     * @return points every {@code step} along the line (the last gap may be shorter)
     */
    public static List<Point> resample(List<Point> line, double step) {
        List<Point> out = new ArrayList<>();
        out.add(line.get(0));
        double carried = 0; // distance travelled since the last emitted point
        for (int i = 1; i < line.size(); i++) {
            Point a = line.get(i - 1), b = line.get(i);
            double seg = PointIndex.distance(a, b);
            double pos = step - carried;
            while (pos <= seg) {
                double t = pos / seg;
                out.add(new Point(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y())));
                pos += step;
            }
            carried = seg - (pos - step);
        }
        Point last = line.get(line.size() - 1);
        if (PointIndex.distance(out.get(out.size() - 1), last) > 1e-9) {
            out.add(last);
        }
        return out;
    }

    /**
     * Keeps the first part of a polyline.
     *
     * @param line the polyline
     * @param fraction how much of its length to keep, 0–1
     * @return the polyline cut at {@code fraction} of its length
     */
    public static List<Point> truncate(List<Point> line, double fraction) {
        double target = length(line) * fraction, run = 0;
        List<Point> out = new ArrayList<>(List.of(line.get(0)));
        for (int i = 1; i < line.size(); i++) {
            Point a = line.get(i - 1), b = line.get(i);
            double seg = PointIndex.distance(a, b);
            if (run + seg >= target) {
                double t = seg == 0 ? 0 : (target - run) / seg;
                out.add(new Point(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y())));
                return out;
            }
            out.add(b);
            run += seg;
        }
        return out;
    }
}
