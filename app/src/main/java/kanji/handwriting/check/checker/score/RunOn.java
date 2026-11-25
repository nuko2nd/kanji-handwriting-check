package kanji.handwriting.check.checker.score;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import kanji.handwriting.check.checker.align.SimilarityTransform;
import kanji.handwriting.check.checker.geometry.PointIndex;
import kanji.handwriting.check.checker.image.InkImage;
import kanji.handwriting.check.checker.reference.ReferenceShape;
import kanji.handwriting.check.model.Point;

/**
 * How drawn ink carries on past the ends of the snapped strokes.
 *
 * <ul>
 *   <li>{@link #continuations}: the ink that just continues a stroke past its end, followed along
 *       the drawn line (so a run-on that curves away is followed too), and never past another
 *       stroke. A separate mark is not connected to the stroke, so it is not reached.
 *   <li>{@link #overshoots}: where a stroke meant to stop at another stroke (as 日's middle line
 *       stops at its frame) is drawn on through it.
 * </ul>
 */
public final class RunOn {
    /** Ink this close (reference units) to a snapped stroke belongs to that stroke. */
    static final double ON_STROKE = 2.5;
    /** A continuation starts within this distance of the stroke's end. */
    static final double START = 5;
    /** A stroke end this close to another stroke is a junction: the stroke stops at that stroke. */
    static final double JUNCTION = 3;
    /** Ink drawn on past a junction for more than this many units is an overshoot. */
    static final double OVERSHOOT = 4;

    private RunOn() {}

    /** The snapped strokes, flattened, with an index of their sample points and each point's stroke. */
    private record Strokes(List<ReferenceShape.Stroke> list, PointIndex index, List<Point> points, int[] owner) {
        static Strokes of(ReferenceShape shape) {
            List<ReferenceShape.Stroke> list = new ArrayList<>();
            shape.components().forEach(c -> list.addAll(c.strokes()));
            List<Point> pts = new ArrayList<>();
            List<Integer> own = new ArrayList<>();
            for (int k = 0; k < list.size(); k++) {
                for (Point p : list.get(k).samples()) {
                    pts.add(p);
                    own.add(k);
                }
            }
            return new Strokes(list, new PointIndex(pts, 4), pts, own.stream().mapToInt(Integer::intValue).toArray());
        }

        /** The stroke nearest {@code p} within {@code radius}, or -1. */
        int near(Point p, double radius) {
            int n = index.nearest(p, radius);
            return n < 0 ? -1 : owner[n];
        }
    }

    /**
     * Ink that carries each snapped stroke on past its ends.
     *
     * @param snapped the snapped strokes
     * @param ink the drawing
     * @param transform drawing pixels' units into reference space
     * @param fraction a continuation may reach this fraction of the stroke's length from its end...
     * @param minReach ...or at least this many units
     * @return the continuation points, in reference space
     */
    public static List<Point> continuations(ReferenceShape snapped, InkImage ink, SimilarityTransform transform,
                                            double fraction, double minReach) {
        return continuations(snapped, ink, transform, fraction, minReach, 180);
    }

    /**
     * Ink that carries each snapped stroke on past its ends, keeping roughly its direction.
     *
     * @param snapped the snapped strokes
     * @param ink the drawing
     * @param transform drawing pixels' units into reference space
     * @param fraction a continuation may reach this fraction of the stroke's length from its end...
     * @param minReach ...or at least this many units
     * @param maxTurnDegrees continuation ink must lie within this angle of the stroke's direction
     *     out of its end (seen from the end); 180 follows the ink wherever it goes. A sharp turn is
     *     another stroke joined on (彳's second stroke on 亻), not the stroke drawn longer
     * @return the continuation points, in reference space
     */
    public static List<Point> continuations(ReferenceShape snapped, InkImage ink, SimilarityTransform transform,
                                            double fraction, double minReach, double maxTurnDegrees) {
        Strokes strokes = Strokes.of(snapped);
        Point[][] pos = positions(ink, transform);
        boolean[][] seen = new boolean[ink.height()][ink.width()];
        List<Point> out = new ArrayList<>();
        double minCos = Math.cos(Math.toRadians(Math.min(180, maxTurnDegrees)));
        for (int k = 0; k < strokes.list().size(); k++) {
            List<Point> path = strokes.list().get(k).path();
            double reach = Math.max(fraction * PointIndexLength.of(path), minReach);
            for (boolean atStart : new boolean[] {true, false}) {
                Point end = atStart ? path.get(0) : path.get(path.size() - 1);
                double[] dir = direction(path, atStart);
                ArrayDeque<int[]> queue = new ArrayDeque<>();
                for (int y = 0; y < ink.height(); y++) {
                    for (int x = 0; x < ink.width(); x++) {
                        if (ink.skeleton()[y][x] && !seen[y][x] && PointIndex.distance(pos[y][x], end) <= START
                                && strokes.near(pos[y][x], ON_STROKE) < 0) {
                            seen[y][x] = true;
                            queue.add(new int[] {x, y});
                        }
                    }
                }
                while (!queue.isEmpty()) {
                    int[] p = queue.poll();
                    out.add(pos[p[1]][p[0]]);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = p[0] + dx, ny = p[1] + dy;
                            if (nx < 0 || ny < 0 || nx >= ink.width() || ny >= ink.height() || seen[ny][nx]
                                    || !ink.skeleton()[ny][nx]) {
                                continue;
                            }
                            Point q = pos[ny][nx];
                            // Stop at any stroke (another stroke: anything beyond is not this
                            // stroke's run-on; this stroke: its own ink is explained already).
                            if (PointIndex.distance(q, end) > reach || strokes.near(q, ON_STROKE) >= 0
                                    || !withinCone(q, end, dir, minCos)) {
                                continue;
                            }
                            seen[ny][nx] = true;
                            queue.add(new int[] {nx, ny});
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Whether {@code q} lies within the cone around {@code dir} from {@code end} (always, close to the end). */
    private static boolean withinCone(Point q, Point end, double[] dir, double minCos) {
        if (dir == null || minCos <= -1) {
            return true;
        }
        double dx = q.x() - end.x(), dy = q.y() - end.y(), d = Math.hypot(dx, dy);
        return d <= START || (dx * dir[0] + dy * dir[1]) / d >= minCos;
    }

    /** A model stroke end at least this far past a crossing must be drawn past it (夫's top). */
    static final double PROTRUSION = 10;
    /** The drawn ink must carry on past the crossing for at least this fraction of the model's distance. */
    static final double PROTRUSION_KEPT = 0.35;
    /** Ink this close past a crossing may belong to the crossing stroke and does not count as carrying on. */
    static final double CROSSING_MARGIN = 3.5;

    /**
     * Strokes meant to cross another stroke and carry on past it (夫's ㇒ through its top line)
     * that stop at the crossing instead (天 for 夫). For each free end of a model stroke at least
     * {@link #PROTRUSION} past the nearest stroke it crosses, the drawn ink must carry on past that
     * crossing (where the snapped strokes cross) for at least {@link #PROTRUSION_KEPT} of the
     * model's distance. The distance comes from the model, not the snapped stroke: snapping moves
     * a stroke drawn too short onto the ink, so its snapped end sits at the crossing.
     *
     * @param model the reference strokes before snapping, in the same space as {@code snapped}
     * @param snapped the snapped strokes
     * @param alignedSkeleton the drawing's skeleton points in reference space
     * @return the 0-based indexes of strokes that do not carry on past a crossing
     */
    public static List<Integer> missingProtrusions(ReferenceShape model, ReferenceShape snapped, List<Point> alignedSkeleton) {
        Strokes before = Strokes.of(model), after = Strokes.of(snapped);
        PointIndex skeleton = new PointIndex(alignedSkeleton, 4);
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < before.list().size() && k < after.list().size(); k++) {
            // Samples, not path vertices: a straight stroke has only two vertices, and walking
            // them would step straight over a crossing.
            List<Point> path = before.list().get(k).samples(), drawn = after.list().get(k).samples();
            if (path.size() < 2 || drawn.size() < 2) {
                continue;
            }
            for (boolean atStart : new boolean[] {true, false}) {
                Point end = atStart ? path.get(0) : path.get(path.size() - 1);
                if (nearOther(before, end, k, JUNCTION) >= 0) {
                    continue; // the end is at a junction, not past one
                }
                // In the model: how far the end lies past the first stroke it crosses.
                double walked = 0;
                Point prev = end;
                int crossing = -1;
                for (int i = 0; i < path.size() && crossing < 0; i++) {
                    Point q = atStart ? path.get(i) : path.get(path.size() - 1 - i);
                    walked += PointIndex.distance(prev, q);
                    prev = q;
                    crossing = nearOther(before, q, k, ON_STROKE);
                }
                // A real crossing has the stroke carry on beyond it too: one that merely starts or
                // ends on the other stroke (休's ㇏ leaving its vertical) is a junction, not a crossing.
                double total = PointIndexLength.of(path);
                if (crossing < 0 || walked < PROTRUSION || total - walked < PROTRUSION) {
                    continue;
                }
                // In the drawing: where the snapped stroke meets the snapped crossing stroke, and
                // which way it heads from there towards this end.
                List<Point> cross = after.list().get(crossing).samples();
                PointIndex crossIndex = new PointIndex(cross, 4);
                int meet = -1;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < drawn.size(); i++) {
                    int n = crossIndex.nearest(drawn.get(i), 2 * ON_STROKE);
                    if (n >= 0) {
                        double d = PointIndex.distance(drawn.get(i), cross.get(n));
                        if (d < best) {
                            best = d;
                            meet = i;
                        }
                    }
                }
                if (meet < 0) {
                    continue; // the snapped strokes do not meet: nothing to measure from
                }
                // March out from the crossing along the snapped stroke (it follows the ink, so a
                // curving ㇒ is followed), then straight on past its end, and see how far the ink
                // carries on. Ink within CROSSING_MARGIN of the crossing may be the crossing
                // stroke's own (its pen width, or a snap a little off the ink), so it does not
                // count; stretches beside a third stroke (天's ㇏ leaving its ㇒) are bridged
                // rather than ending the march.
                List<Point> march = marchFrom(drawn, meet, atStart, walked * 1.2);
                double reach = 0, inked = 0;
                for (int m = 0; m < march.size() && (m + 1) * MARCH_STEP - reach <= 2.5; m++) {
                    double t = (m + 1) * MARCH_STEP;
                    Point q = march.get(m);
                    boolean beside = nearOther(after, q, k, ON_STROKE) >= 0;
                    if (t <= CROSSING_MARGIN || beside) {
                        reach = t;
                    } else if (skeleton.anyWithin(q, ON_STROKE)) {
                        reach = t;
                        inked = t;
                    }
                }
                if (inked < Math.max(CROSSING_MARGIN, PROTRUSION_KEPT * walked)) {
                    out.add(after.list().get(k).index());
                    break;
                }
            }
        }
        return out;
    }

    /** Spacing, in reference units, of the points {@link #marchFrom} returns. */
    private static final double MARCH_STEP = 0.5;

    /**
     * Points every {@link #MARCH_STEP} units from path index {@code i} along the path towards its
     * start (or end), then straight on past that end, up to {@code length} units in all.
     */
    private static List<Point> marchFrom(List<Point> path, int i, boolean towardsStart, double length) {
        List<Point> out = new ArrayList<>();
        int step = towardsStart ? -1 : 1;
        Point cur = path.get(i);
        double carried = 0; // distance walked since the last emitted point
        for (int m = i; m + step >= 0 && m + step < path.size() && out.size() * MARCH_STEP < length; m += step) {
            Point next = path.get(m + step);
            double seg = PointIndex.distance(cur, next);
            double t = MARCH_STEP - carried;
            while (t <= seg && out.size() * MARCH_STEP < length) {
                double f = t / seg;
                out.add(new Point(cur.x() + (next.x() - cur.x()) * f, cur.y() + (next.y() - cur.y()) * f));
                t += MARCH_STEP;
            }
            carried = seg - (t - MARCH_STEP);
            cur = next;
        }
        double[] dir = direction(path, towardsStart);
        Point end = towardsStart ? path.get(0) : path.get(path.size() - 1);
        for (double t = MARCH_STEP - carried; dir != null && out.size() * MARCH_STEP < length; t += MARCH_STEP) {
            out.add(at(end, dir, t));
        }
        return out;
    }

    /**
     * Strokes drawn on through a stroke they should stop at: at each stroke end that meets
     * another stroke (a junction), whether drawn ink continues straight on beyond that stroke for
     * more than {@link #OVERSHOOT} units.
     *
     * @param snapped the snapped strokes
     * @param alignedSkeleton the drawing's skeleton points in reference space
     * @return the 0-based indexes of overshooting strokes
     */
    public static List<Integer> overshoots(ReferenceShape snapped, List<Point> alignedSkeleton) {
        Strokes strokes = Strokes.of(snapped);
        PointIndex skeleton = new PointIndex(alignedSkeleton, 4);
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < strokes.list().size(); k++) {
            ReferenceShape.Stroke stroke = strokes.list().get(k);
            List<Point> path = stroke.path();
            for (boolean atStart : new boolean[] {true, false}) {
                Point end = atStart ? path.get(0) : path.get(path.size() - 1);
                int self = k;
                int other = nearOther(strokes, end, self, JUNCTION);
                if (other < 0) {
                    continue; // a free end: length is judged elsewhere
                }
                double[] dir = direction(path, atStart);
                if (dir == null) {
                    continue;
                }
                // Reach the other stroke, cross it, then measure how far ink carries straight on.
                double t = 0;
                while (t <= JUNCTION + 1 && nearOther(strokes, at(end, dir, t), self, ON_STROKE) < 0) {
                    t += 0.5;
                }
                while (t <= JUNCTION + 8 && nearOther(strokes, at(end, dir, t), self, ON_STROKE) >= 0) {
                    t += 0.5;
                }
                double start = t, last = t;
                for (; t <= start + 4 * OVERSHOOT && t - last <= 2; t += 0.5) {
                    Point q = at(end, dir, t);
                    if (skeleton.anyWithin(q, 1.5) && strokes.near(q, ON_STROKE) < 0) {
                        last = t;
                    }
                }
                if (last - start > OVERSHOOT) {
                    out.add(stroke.index());
                    break;
                }
            }
        }
        return out;
    }

    /** The nearest other stroke within {@code radius} of {@code p}, or -1. */
    private static int nearOther(Strokes strokes, Point p, int self, double radius) {
        int best = -1;
        double bestD = radius;
        for (int i = 0; i < strokes.points().size(); i++) {
            if (strokes.owner()[i] == self) {
                continue;
            }
            double d = PointIndex.distance(p, strokes.points().get(i));
            if (d <= bestD) {
                bestD = d;
                best = strokes.owner()[i];
            }
        }
        return best;
    }

    private static Point at(Point end, double[] dir, double t) {
        return new Point(end.x() + dir[0] * t, end.y() + dir[1] * t);
    }

    /** Unit direction of travel out of the path's end, from a point about 5 units back. */
    private static double[] direction(List<Point> path, boolean atStart) {
        Point end = atStart ? path.get(0) : path.get(path.size() - 1), back = end;
        double walked = 0;
        for (int i = 1; i < path.size() && walked < 5; i++) {
            Point q = atStart ? path.get(i) : path.get(path.size() - 1 - i);
            walked += PointIndex.distance(q, back);
            back = q;
        }
        double dx = end.x() - back.x(), dy = end.y() - back.y(), d = Math.hypot(dx, dy);
        return d < 1e-6 ? null : new double[] {dx / d, dy / d};
    }

    private static Point[][] positions(InkImage ink, SimilarityTransform transform) {
        Point[][] pos = new Point[ink.height()][ink.width()];
        double u = ink.unitsPerPixel();
        for (int y = 0; y < ink.height(); y++) {
            for (int x = 0; x < ink.width(); x++) {
                if (ink.skeleton()[y][x]) {
                    pos[y][x] = transform.apply(new Point((x + 0.5) * u, (y + 0.5) * u));
                }
            }
        }
        return pos;
    }

    /** Polyline length. */
    private static final class PointIndexLength {
        static double of(List<Point> path) {
            double sum = 0;
            for (int i = 1; i < path.size(); i++) {
                sum += PointIndex.distance(path.get(i - 1), path.get(i));
            }
            return sum;
        }
    }
}
