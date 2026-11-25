package kanji.handwriting.check.checker.score;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.checker.geometry.PointIndex;
import kanji.handwriting.check.checker.reference.ReferenceShape;
import kanji.handwriting.check.model.Point;

/**
 * Drawn ink that no snapped stroke accounts for, judged by direction as well as distance.
 *
 * <p>Ink close to a stroke counts as that stroke's only if it runs the same way. An extra short
 * line crossing a stroke (体's bar drawn on 休's 木), or one beside a stroke (a second sweep making
 * 亻 into 彳), lies within the stroke tolerance and so looks explained by distance alone; it runs
 * across the stroke, or sits between two strokes farther than the tolerance from each (日 drawn
 * inside 回). Ink near a stroke's end (hooks, flicks, run-on), at junctions and crossings (where
 * the direction is ambiguous), and stray marks far from every stroke (judged elsewhere) is
 * ignored.
 */
public final class ExtraInk {
    /** Ink running more than this many degrees off the nearby strokes is not theirs. */
    static final double ANGLE = 40;
    /** Ink this close to a stroke end is that stroke's hook, flick or run-on. */
    static final double END_ZONE = 5;
    /** Radius, in reference units, over which the ink's local direction is measured. */
    static final double DIRECTION_RADIUS = 2.5;

    private ExtraInk() {}

    /**
     * The length of the largest connected piece of drawn ink that no snapped stroke accounts for.
     *
     * @param report a snap score report
     * @param config tolerances ({@code tolerance}, {@code strayDistance})
     * @return the piece's skeleton length, in reference units (0 if there is none)
     */
    public static double largestPiece(ScoreReport report, CheckerConfig config) {
        List<ReferenceShape.Stroke> strokes = new ArrayList<>();
        report.scoredShape().components().forEach(c -> strokes.addAll(c.strokes()));
        List<Point> samples = new ArrayList<>(), ends = new ArrayList<>();
        List<double[]> tangents = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        for (int s = 0; s < strokes.size(); s++) {
            List<Point> p = strokes.get(s).samples();
            if (p.size() < 2) {
                continue;
            }
            ends.add(p.get(0));
            ends.add(p.get(p.size() - 1));
            for (int i = 0; i < p.size(); i++) {
                Point u = p.get(Math.max(0, i - 2)), v = p.get(Math.min(p.size() - 1, i + 2));
                double dx = v.x() - u.x(), dy = v.y() - u.y(), d = Math.hypot(dx, dy);
                samples.add(p.get(i));
                tangents.add(d < 1e-9 ? new double[] {1, 0} : new double[] {dx / d, dy / d});
                owner.add(s);
            }
        }
        List<Point> skel = report.alignedSkeleton();
        if (skel.isEmpty() || samples.isEmpty()) {
            return 0;
        }
        double tau = config.tolerance(), minCos = Math.cos(Math.toRadians(ANGLE));
        PointIndex sampleIndex = new PointIndex(samples, tau), endIndex = new PointIndex(ends, END_ZONE);
        PointIndex skelIndex = new PointIndex(skel, DIRECTION_RADIUS);
        double step = spacing(skel, skelIndex);

        boolean[] bad = new boolean[skel.size()];
        for (int i = 0; i < skel.size(); i++) {
            Point q = skel.get(i);
            if (endIndex.anyWithin(q, END_ZONE)) {
                continue;
            }
            double[] dir = inkDirection(skel, skelIndex, q);
            Set<Integer> near = new HashSet<>();
            boolean sameWay = false;
            for (int k : within(sampleIndex, samples, q, tau)) {
                near.add(owner.get(k));
                double[] t = tangents.get(k);
                if (dir == null || Math.abs(t[0] * dir[0] + t[1] * dir[1]) >= minCos) {
                    sameWay = true;
                }
            }
            boolean junction = near.size() >= 2;
            boolean stray = near.isEmpty() && !sampleIndex.anyWithin(q, config.strayDistance());
            bad[i] = !sameWay && !junction && !stray;
        }

        // Largest connected piece of unexplained ink.
        double best = 0;
        boolean[] seen = new boolean[skel.size()];
        for (int i = 0; i < skel.size(); i++) {
            if (!bad[i] || seen[i]) {
                continue;
            }
            int count = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            seen[i] = true;
            while (!queue.isEmpty()) {
                int c = queue.poll();
                count++;
                for (int j : within(skelIndex, skel, skel.get(c), 1.6 * step)) {
                    if (bad[j] && !seen[j]) {
                        seen[j] = true;
                        queue.add(j);
                    }
                }
            }
            best = Math.max(best, count * step);
        }
        return best;
    }

    /** The principal direction of the skeleton around {@code q}, or null if too few points. */
    private static double[] inkDirection(List<Point> skel, PointIndex index, Point q) {
        List<Integer> nb = within(index, skel, q, DIRECTION_RADIUS);
        if (nb.size() < 3) {
            return null;
        }
        double mx = 0, my = 0;
        for (int j : nb) {
            mx += skel.get(j).x();
            my += skel.get(j).y();
        }
        mx /= nb.size();
        my /= nb.size();
        double sxx = 0, syy = 0, sxy = 0;
        for (int j : nb) {
            double dx = skel.get(j).x() - mx, dy = skel.get(j).y() - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        double th = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        return new double[] {Math.cos(th), Math.sin(th)};
    }

    /**
     * Indexes of the points within {@code r} of {@code q}. A plain scan ({@link PointIndex} only
     * answers nearest-point queries); the point lists here are a few thousand points at most.
     */
    private static List<Integer> within(PointIndex index, List<Point> pts, Point q, double r) {
        List<Integer> out = new ArrayList<>();
        for (int j = 0; j < pts.size(); j++) {
            Point p = pts.get(j);
            if (Math.abs(p.x() - q.x()) <= r && Math.abs(p.y() - q.y()) <= r && PointIndex.distance(p, q) <= r) {
                out.add(j);
            }
        }
        return out;
    }

    /** Typical spacing between neighbouring skeleton points, in reference units. */
    private static double spacing(List<Point> skel, PointIndex index) {
        List<Double> d = new ArrayList<>();
        for (int i = 0; i < skel.size(); i += Math.max(1, skel.size() / 200)) {
            double best = Double.MAX_VALUE;
            for (int j : within(index, skel, skel.get(i), 3)) {
                double dist = PointIndex.distance(skel.get(i), skel.get(j));
                if (dist > 1e-9) {
                    best = Math.min(best, dist);
                }
            }
            if (best < Double.MAX_VALUE) {
                d.add(best);
            }
        }
        if (d.isEmpty()) {
            return 1;
        }
        d.sort(null);
        return d.get(d.size() / 2);
    }
}
