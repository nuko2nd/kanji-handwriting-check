package kanji.handwriting.check.checker.align;

import java.util.ArrayList;
import java.util.List;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.checker.geometry.PointIndex;
import kanji.handwriting.check.checker.image.InkPreprocessor;
import kanji.handwriting.check.model.Point;

/**
 * Bounded ICP (iterative closest point) with a similarity transform. The drawing is assumed to
 * be roughly in place already, because it was drawn inside the grid, so the fit only refines it,
 * within the configured limits.
 *
 * <ol>
 *   <li><b>Starting points.</b> ICP starts with the drawing scaled about the cell centre by the
 *       ratio of the reference's RMS radius to the drawing's (each about its own centroid).
 *       Starting from the raw drawing instead lets nearest-point matching pull a too-small drawing
 *       further inward. If the drawing's centroid is farther from the reference's than
 *       {@code offCentreNote}, a second start also moves the centroid into place: ICP alone
 *       cannot see a whole-drawing shift larger than {@code icpMaxPairDistance}. Smaller offsets,
 *       such as one shifted component or a dropped stroke, are left to ICP from the in-place
 *       start, so they do not pull the whole drawing off.
 *   <li><b>ICP iterations</b>: pair every drawing point with its nearest reference point (pairs
 *       farther apart than {@code icpMaxPairDistance} are ignored), then solve for the best
 *       similarity transform in closed form (weighted 2-D Umeyama/Procrustes). Pairs are weighted
 *       by {@code 1 / (1 + (d/σ)²)²} with {@code σ = tolerance / 2}, so points that already fit
 *       dominate and one misplaced component does not drag the rest.
 *   <li><b>Choice, then limits.</b> Each start is first run without the configured limits, and
 *       the run with the better two-sided fit (see {@code fitQuality}) wins; the in-place start
 *       wins ties. Comparing unclamped fits matters: a drawing that really is too small or off
 *       centre fits badly once clamped, and would otherwise lose to a distorted fit that happens
 *       to stay inside the limits. The winner is then clamped to the limits.
 * </ol>
 * A limit counts as hit if the chosen unclamped fit goes beyond it; {@link Alignment#wanted()}
 * is that unclamped fit.
 *
 * <p><b>Outline normalisation.</b> Size and position in the grid are not scored. If the drawing
 * is so small, large or far off centre that even the starting scale or centroid shift is beyond
 * the scale or translation limits (e.g. a tiny character in one corner), it is first scaled and
 * moved so that its RMS radius and centroid match the reference's, and the steps above then run
 * on that. The scale and translation limits are reported as hit; rotation is still limited.
 */
public final class Aligner {
    private static final int MAX_POINTS = 3000;

    private Aligner() {}

    /**
     * Aligns drawing points to reference points.
     *
     * @param drawing skeleton points of the drawing, in reference space
     * @param reference sampled reference stroke points
     * @param config the limits and iteration settings
     * @return the clamped transform and which limits were hit
     */
    public static Alignment align(List<Point> drawing, List<Point> reference, CheckerConfig config) {
        double c = InkPreprocessor.REFERENCE_SIZE / 2;
        if (drawing.size() < 3) {
            SimilarityTransform id = SimilarityTransform.identity(c, c);
            return new Alignment(id, id, false, false, false, 0);
        }
        // Starting points: size-corrected in place; and, only if the drawing's centroid is farther
        // off than the translation limit, also size- and centroid-corrected. (Starting from the raw
        // drawing lets a too-small drawing collapse onto the inner strokes.)
        double s0 = rmsRadius(reference) / rmsRadius(drawing);
        Point mr = centroid(reference), md = centroid(drawing);
        double cx = mr.x() - c - s0 * (md.x() - c), cy = mr.y() - c - s0 * (md.y() - c);
        boolean scaleOutside = s0 > config.scaleMax() || s0 < config.scaleMin();
        boolean shiftOutside = Math.max(Math.abs(cx), Math.abs(cy)) > config.maxTranslation();
        if (scaleOutside || shiftOutside) {
            // Outline normalisation: afterwards s0 is 1 and the centroids coincide, so this
            // happens at most once.
            SimilarityTransform pre = new SimilarityTransform(s0, 0, cx, cy, c, c);
            Alignment inner = align(pre.apply(drawing), reference, config);
            return new Alignment(inner.transform().after(pre), inner.wanted().after(pre),
                    scaleOutside || inner.scaleClamped(), inner.rotationClamped(),
                    shiftOutside || inner.translationClamped(), inner.iterations());
        }
        List<SimilarityTransform> starts = new ArrayList<>(List.of(new SimilarityTransform(s0, 0, 0, 0, c, c)));
        if (Math.max(Math.abs(cx), Math.abs(cy)) > config.offCentreNote()) {
            starts.add(new SimilarityTransform(s0, 0, cx, cy, c, c));
        }

        // Choose the start by its unclamped fit, so a drawing that really is off by more than a
        // limit is not out-scored by a distorted fit that stays inside the limits.
        List<Point> pts = subsample(drawing);
        PointIndex refIndex = new PointIndex(reference, config.icpMaxPairDistance());
        // A third start from a coarse search over scale and shift: a drawing placed high and
        // stretched (each drawn line nearest the next model line down) otherwise settles on a
        // locally consistent but wrong match.
        starts.add(gridStart(pts, reference, refIndex, s0, c, config));
        Alignment best = null;
        double bestFit = -1;
        for (SimilarityTransform start : starts) {
            Alignment free = icp(start, pts, reference, refIndex, config, false);
            double fit = fitQuality(free.transform(), pts, reference, refIndex, config);
            // The coarse-search start (last) must win clearly: it helps when the drawing is far off,
            // but a marginally better global fit can still lead to a worse snap.
            double preference = start == starts.get(starts.size() - 1) ? GRID_PREFERENCE : START_PREFERENCE;
            if (best == null || fit > bestFit * (1 + preference)) {
                best = free;
                bestFit = fit;
            }
        }
        // Clamp the chosen fit. Only what the unclamped fit wanted is reported: re-fitting inside
        // the limits would trade one clamped parameter for another (e.g. a spurious tilt when the
        // scale is capped).
        boolean[] exceeded = new boolean[3];
        SimilarityTransform clamped = clamp(best.transform(), config, exceeded);
        return new Alignment(clamped, best.transform(), exceeded[0], exceeded[1], exceeded[2], best.iterations());
    }

    /** Coarse grid over scale (around the RMS estimate) and shift, scored by the two-sided fit. */
    private static SimilarityTransform gridStart(List<Point> pts, List<Point> reference, PointIndex refIndex, double s0,
                                                 double c, CheckerConfig config) {
        List<Point> probe = pts.size() > 200 ? subsampleTo(pts, 200) : pts;
        List<Point> refProbe = subsampleTo(reference, 300);
        SimilarityTransform best = new SimilarityTransform(s0, 0, 0, 0, c, c);
        double bestFit = -1;
        double maxT = config.maxTranslation();
        for (double k = 0.8; k <= 1.25 + 1e-9; k *= 1.077) {
            double s = Math.max(config.scaleMin(), Math.min(config.scaleMax(), s0 * k));
            for (double tx = -maxT; tx <= maxT + 1e-9; tx += maxT / 10) {
                for (double ty = -maxT; ty <= maxT + 1e-9; ty += maxT / 10) {
                    SimilarityTransform t = new SimilarityTransform(s, 0, tx, ty, c, c);
                    double fit = fitQuality(t, probe, refProbe, refIndex, config);
                    if (fit > bestFit) {
                        bestFit = fit;
                        best = t;
                    }
                }
            }
        }
        return best;
    }

    private static List<Point> subsampleTo(List<Point> pts, int max) {
        if (pts.size() <= max) {
            return pts;
        }
        List<Point> out = new ArrayList<>();
        double step = pts.size() / (double) max;
        for (double i = 0; i < pts.size(); i += step) {
            out.add(pts.get((int) i));
        }
        return out;
    }

    /** How much better (relative) a later start's fit must be to replace an earlier one. */
    private static final double START_PREFERENCE = 0.01;

    /** How much better the coarse-search start's fit must be to be chosen. */
    private static final double GRID_PREFERENCE = 0.05;

    /** Wide sanity limits for the unclamped selection runs, so a bad start cannot run away. */
    private static final double FREE_SCALE = 2, FREE_ROTATION_DEG = 30, FREE_TRANSLATION = 40;

    private static Alignment icp(SimilarityTransform start, List<Point> pts, List<Point> reference,
                                 PointIndex refIndex, CheckerConfig config, boolean bounded) {
        if (!bounded) {
            config = config.toBuilder().scaleMin(1 / FREE_SCALE).scaleMax(FREE_SCALE)
                    .maxRotationDegrees(FREE_ROTATION_DEG).maxTranslation(FREE_TRANSLATION).build();
        }
        double c = start.cx();
        boolean[] clamps = new boolean[3];
        SimilarityTransform wanted = start;
        SimilarityTransform current = clamp(start, config, clamps);
        int it = 0;
        for (; it < config.icpIterations(); it++) {
            List<Point> from = new ArrayList<>(), to = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (Point p : pts) {
                Point q = current.apply(p);
                int n = refIndex.nearest(q, config.icpMaxPairDistance());
                if (n >= 0) {
                    from.add(p);
                    to.add(reference.get(n));
                    weights.add(weight(PointIndex.distance(q, reference.get(n)), config));
                }
            }
            if (from.size() < 3) {
                break;
            }
            wanted = fit(from, to, weights, c, c);
            SimilarityTransform next = clamp(wanted, config, clamps);
            boolean converged = Math.abs(next.scale() - current.scale()) < 1e-4
                    && Math.abs(next.rotation() - current.rotation()) < 1e-5
                    && Math.abs(next.tx() - current.tx()) < 1e-3 && Math.abs(next.ty() - current.ty()) < 1e-3;
            current = next;
            if (converged) {
                it++;
                break;
            }
        }
        return new Alignment(current, wanted, clamps[0], clamps[1], clamps[2], it);
    }

    /** Robust pair weight 1 / (1 + (d/σ)²)² with σ = τ/2. */
    private static double weight(double d, CheckerConfig config) {
        double u = d / (config.tolerance() / 2);
        return 1 / Math.pow(1 + u * u, 2);
    }

    /**
     * Two-sided fit: the mean robust weight of drawing points to their nearest reference point,
     * averaged with the same for reference points to their nearest drawing point (0 where nothing
     * is within {@code icpMaxPairDistance}). One-sided matching would reward collapsing a small
     * drawing onto the inner strokes; the reverse direction penalises the strokes left uncovered.
     */
    private static double fitQuality(SimilarityTransform t, List<Point> pts, List<Point> reference,
                                     PointIndex refIndex, CheckerConfig config) {
        List<Point> moved = t.apply(pts);
        PointIndex drawingIndex = new PointIndex(moved, config.icpMaxPairDistance());
        return (meanWeight(moved, refIndex, config) + meanWeight(subsample(reference), drawingIndex, config)) / 2;
    }

    private static double meanWeight(List<Point> from, PointIndex to, CheckerConfig config) {
        double sum = 0;
        for (Point p : from) {
            int n = to.nearest(p, config.icpMaxPairDistance());
            if (n >= 0) {
                sum += weight(PointIndex.distance(p, to.points().get(n)), config);
            }
        }
        return sum / from.size();
    }

    private static Point centroid(List<Point> pts) {
        double x = 0, y = 0;
        for (Point p : pts) {
            x += p.x();
            y += p.y();
        }
        return new Point(x / pts.size(), y / pts.size());
    }

    /** Root-mean-square distance of the points from their centroid. */
    static double rmsRadius(List<Point> pts) {
        double mx = 0, my = 0;
        for (Point p : pts) {
            mx += p.x();
            my += p.y();
        }
        mx /= pts.size();
        my /= pts.size();
        double s = 0;
        for (Point p : pts) {
            s += (p.x() - mx) * (p.x() - mx) + (p.y() - my) * (p.y() - my);
        }
        return Math.sqrt(s / pts.size());
    }

    private static List<Point> subsample(List<Point> pts) {
        if (pts.size() <= MAX_POINTS) {
            return pts;
        }
        int step = (int) Math.ceil(pts.size() / (double) MAX_POINTS);
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i += step) {
            out.add(pts.get(i));
        }
        return out;
    }

    /** Weighted least-squares similarity transform about centre (cx, cy) mapping from[i] onto to[i]. */
    static SimilarityTransform fit(List<Point> from, List<Point> to, List<Double> weights, double cx, double cy) {
        int n = from.size();
        double max = 0, may = 0, mbx = 0, mby = 0, wsum = 0;
        for (int i = 0; i < n; i++) {
            double w = weights.get(i);
            max += w * (from.get(i).x() - cx);
            may += w * (from.get(i).y() - cy);
            mbx += w * (to.get(i).x() - cx);
            mby += w * (to.get(i).y() - cy);
            wsum += w;
        }
        max /= wsum;
        may /= wsum;
        mbx /= wsum;
        mby /= wsum;
        double sxx = 0, sxy = 0, var = 0;
        for (int i = 0; i < n; i++) {
            double w = weights.get(i);
            double ax = from.get(i).x() - cx - max, ay = from.get(i).y() - cy - may;
            double bx = to.get(i).x() - cx - mbx, by = to.get(i).y() - cy - mby;
            sxx += w * (ax * bx + ay * by);
            sxy += w * (ax * by - ay * bx);
            var += w * (ax * ax + ay * ay);
        }
        double rotation = Math.atan2(sxy, sxx);
        double scale = var == 0 ? 1 : Math.hypot(sxx, sxy) / var;
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        double tx = mbx - scale * (cos * max - sin * may);
        double ty = mby - scale * (sin * max + cos * may);
        return new SimilarityTransform(scale, rotation, tx, ty, cx, cy);
    }

    /** Clamps each parameter, recording in clamps[0..2] whether scale/rotation/translation hit a limit. */
    private static SimilarityTransform clamp(SimilarityTransform t, CheckerConfig config, boolean[] clamps) {
        double maxRot = Math.toRadians(config.maxRotationDegrees()), maxT = config.maxTranslation();
        double scale = Math.max(config.scaleMin(), Math.min(config.scaleMax(), t.scale()));
        double rotation = Math.max(-maxRot, Math.min(maxRot, t.rotation()));
        double tx = Math.max(-maxT, Math.min(maxT, t.tx()));
        double ty = Math.max(-maxT, Math.min(maxT, t.ty()));
        clamps[0] = scale != t.scale();
        clamps[1] = rotation != t.rotation();
        clamps[2] = tx != t.tx() || ty != t.ty();
        return new SimilarityTransform(scale, rotation, tx, ty, t.cx(), t.cy());
    }
}
