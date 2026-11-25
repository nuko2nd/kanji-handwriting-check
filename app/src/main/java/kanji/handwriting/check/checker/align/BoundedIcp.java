package kanji.handwriting.check.checker.align;

import java.util.ArrayList;
import java.util.List;
import kanji.handwriting.check.checker.geometry.PointIndex;
import kanji.handwriting.check.model.Point;

/**
 * Moves a set of points onto a target point set with a similarity transform about a given
 * centre, within limits. Used to snap reference components and strokes onto the drawing's ink.
 *
 * <p>Each iteration pairs every moving point with its nearest target point within
 * {@code pairDistance}, weights the pair by {@code 1 / (1 + (d/σ)²)²}, solves the weighted
 * similarity fit in closed form and clamps it. σ shrinks geometrically from {@code sigmaStart} to
 * {@code sigmaEnd}: early iterations let distant ink pull the points in, and later ones lock onto
 * the closest ink only.
 */
public final class BoundedIcp {
    private BoundedIcp() {}

    /**
     * Limits on the transform.
     *
     * @param maxShift largest length of the translation vector
     * @param maxRotationDegrees largest rotation either way
     * @param maxScaleChange scale stays within {@code [1/(1+c), 1+c]}; 0 forbids scaling
     */
    public record Limits(double maxShift, double maxRotationDegrees, double maxScaleChange) {
        /**
         * Translation only.
         *
         * @param maxShift largest shift
         * @return limits that forbid rotation and scaling
         */
        public static Limits shiftOnly(double maxShift) {
            return new Limits(maxShift, 0, 0);
        }
    }

    /**
     * Outcome of a fit.
     *
     * @param transform the clamped transform
     * @param fit mean pair weight at the final σ over all moving points (0–1; 0 for points with
     *     no target point within {@code pairDistance})
     */
    public record Result(SimilarityTransform transform, double fit) {}

    /**
     * Runs the fit.
     *
     * @param moving points to move
     * @param target index of the points to move onto
     * @param cx transform centre x
     * @param cy transform centre y
     * @param limits bounds on the transform
     * @param pairDistance largest distance at which a pair is formed
     * @param sigmaStart weight scale for the first iteration
     * @param sigmaEnd weight scale for the last iteration
     * @param iterations number of iterations
     * @param reverseTarget if not null, matching is two-sided: every point of this set within
     *     {@code reverseRadius} of the moved points also pulls its nearest moving point toward it.
     *     One-sided matching (moving → target only) lets a stroke shrink freely, because a shorter
     *     stroke still lies on its ink; the reverse pairs make it cover its ink as well. It may
     *     differ from {@code target}, e.g. only the ink not yet claimed by other strokes.
     * @param reverseRadius see {@code reverseTarget}
     * @return the transform and fit quality
     */
    public static Result fit(List<Point> moving, PointIndex target, double cx, double cy, Limits limits,
                             double pairDistance, double sigmaStart, double sigmaEnd, int iterations,
                             PointIndex reverseTarget, double reverseRadius) {
        SimilarityTransform current = SimilarityTransform.identity(cx, cy);
        if (moving.isEmpty() || target.points().isEmpty()) {
            return new Result(current, 0);
        }
        for (int it = 0; it < iterations; it++) {
            Pairs pairs = pairs(moving, current, target, pairDistance, sigma(it, iterations, sigmaStart, sigmaEnd),
                    reverseTarget, reverseRadius);
            if (pairs.from.size() < 2) {
                break;
            }
            SimilarityTransform wanted = Aligner.fit(pairs.from, pairs.to, pairs.weights, cx, cy);
            if (pairs.from.size() < 3) {
                // Too few pairs to judge rotation or scale.
                wanted = new SimilarityTransform(1, 0, wanted.tx(), wanted.ty(), cx, cy);
            }
            current = clamp(wanted, limits);
        }
        return new Result(current, quality(moving, current, target, pairDistance, sigmaEnd));
    }

    /**
     * Outcome of an axis-aligned fit.
     *
     * @param transform the clamped transform
     * @param fit mean pair weight at the final σ, as in {@link Result#fit()}
     */
    public record AxisResult(AxisTransform transform, double fit) {}

    /**
     * Like {@link #fit(List, PointIndex, double, double, Limits, double, double, double, int,
     * PointIndex, double)}, but with separate horizontal and vertical scales and no rotation (an
     * {@link AxisTransform}), for fitting a part of a character whose proportions may differ from
     * the model's.
     *
     * @param moving points to move
     * @param target index of the points to move onto
     * @param cx transform centre x
     * @param cy transform centre y
     * @param maxShift largest length of the translation vector
     * @param maxScaleChange each axis's scale stays within {@code [1/(1+c), 1+c]}
     * @param pairDistance largest distance at which a pair is formed
     * @param sigmaStart weight scale for the first iteration
     * @param sigmaEnd weight scale for the last iteration
     * @param iterations number of iterations
     * @param reverseTarget points that also pull the moving points (two-sided matching), or null
     * @param reverseRadius reach of the reverse pairs
     * @return the transform and fit quality
     */
    public static AxisResult fitAxes(List<Point> moving, PointIndex target, double cx, double cy, double maxShift,
                                     double maxScaleChange, double pairDistance, double sigmaStart, double sigmaEnd,
                                     int iterations, PointIndex reverseTarget, double reverseRadius) {
        return fitAxes(moving, target, new AxisTransform(1, 1, 0, 0, cx, cy), maxShift, maxScaleChange, pairDistance,
                sigmaStart, sigmaEnd, iterations, reverseTarget, reverseRadius);
    }

    /**
     * Coarse search, then {@link #fitAxes}: ICP only finds the nearest good fit, so a part drawn
     * far from the model's proportions (a squashed 五, an enlarged 口) can settle on a wrong but
     * locally consistent match, each of its lines on the next line of ink. This first tries a grid
     * of vertical scales and shifts, then horizontal ones (twice round), scoring how well the
     * moved points lie on the target, and starts ICP from the best.
     *
     * @param moving points to move
     * @param target index of the points to move onto
     * @param cx transform centre x
     * @param cy transform centre y
     * @param maxShift largest length of the translation vector
     * @param maxScaleChange each axis's scale stays within {@code [1/(1+c), 1+c]}
     * @param pairDistance largest distance at which a pair is formed
     * @param sigmaStart weight scale for the first ICP iteration
     * @param sigmaEnd weight scale for the last ICP iteration, and for scoring grid candidates
     * @param iterations number of ICP iterations
     * @param reverseTarget points that also pull the moving points (two-sided matching), or null
     * @param reverseRadius reach of the reverse pairs
     * @return the transform and fit quality
     */
    public static AxisResult searchAxes(List<Point> moving, PointIndex target, double cx, double cy, double maxShift,
                                        double maxScaleChange, double pairDistance, double sigmaStart, double sigmaEnd,
                                        int iterations, PointIndex reverseTarget, double reverseRadius) {
        List<Point> probe = subsample(moving, 150);
        List<Point> own = reverseTarget == null ? List.of() : subsample(reverseTarget.points(), 300);
        AxisTransform best = new AxisTransform(1, 1, 0, 0, cx, cy);
        double bestScore = twoSided(probe, own, best, target, pairDistance, sigmaEnd) - prior(best);
        double maxScale = 1 + maxScaleChange;
        if (!probe.isEmpty() && !target.points().isEmpty()) {
            for (int round = 0; round < 2; round++) {
                for (int axis = 1; axis >= 0; axis--) { // vertical first: stacked parts are the usual case
                    AxisTransform base = best;
                    // Counted loops: with no scale (or shift) freedom the step is zero, and a
                    // stepped loop would never end; this tries the single value instead.
                    int scaleSteps = maxScaleChange > 0 ? 12 : 0, shiftSteps = maxShift > 0 ? 14 : 0;
                    for (int si = 0; si <= scaleSteps; si++) {
                        double s = scaleSteps == 0 ? 1 : Math.exp(Math.log(maxScale) * (si / 6.0 - 1));
                        for (int ti = 0; ti <= shiftSteps; ti++) {
                            double t = shiftSteps == 0 ? 0 : maxShift * (ti / 7.0 - 1);
                            AxisTransform cand = axis == 1
                                    ? new AxisTransform(base.sx(), s, base.tx(), t, cx, cy)
                                    : new AxisTransform(s, base.sy(), t, base.ty(), cx, cy);
                            if (Math.hypot(cand.tx(), cand.ty()) > maxShift + 1e-9) {
                                continue;
                            }
                            double score = twoSided(probe, own, cand, target, pairDistance, sigmaEnd) - prior(cand);
                            if (score > bestScore) {
                                bestScore = score;
                                best = cand;
                            }
                        }
                    }
                }
            }
        }
        return fitAxes(moving, target, best, maxShift, maxScaleChange, pairDistance, sigmaStart, sigmaEnd, iterations,
                reverseTarget, reverseRadius);
    }

    private static AxisResult fitAxes(List<Point> moving, PointIndex target, AxisTransform start, double maxShift,
                                      double maxScaleChange, double pairDistance, double sigmaStart, double sigmaEnd,
                                      int iterations, PointIndex reverseTarget, double reverseRadius) {
        double cx = start.cx(), cy = start.cy();
        AxisTransform current = start;
        if (moving.isEmpty() || target.points().isEmpty()) {
            return new AxisResult(current, 0);
        }
        double maxScale = 1 + maxScaleChange;
        for (int it = 0; it < iterations; it++) {
            // The reverse reach shrinks with σ: early on, ink well beyond the part's current extent
            // (the far side of a 口 drawn taller than the model) pulls it to stretch; later only
            // nearby ink counts.
            double sigma = sigma(it, iterations, sigmaStart, sigmaEnd);
            Pairs pairs = pairs(moving, current, target, pairDistance, sigma, reverseTarget, Math.max(reverseRadius, sigma));
            if (pairs.from.size() < 3) {
                break;
            }
            double[] x = axisFit(pairs, cx, true), y = axisFit(pairs, cy, false);
            double sx = Math.max(1 / maxScale, Math.min(maxScale, x[0]));
            double sy = Math.max(1 / maxScale, Math.min(maxScale, y[0]));
            double tx = x[1] + (x[0] - sx) * x[2], ty = y[1] + (y[0] - sy) * y[2]; // re-solve shift for the clamped scale
            double len = Math.hypot(tx, ty);
            if (len > maxShift) {
                tx *= maxShift / len;
                ty *= maxShift / len;
            }
            current = new AxisTransform(sx, sy, tx, ty, cx, cy);
        }
        return new AxisResult(current, quality(moving, current, target, pairDistance, sigmaEnd));
    }

    /** Weighted 1-D least squares b = c + s·(a − c) + t along one axis: returns {s, t, mean of (a − c)}. */
    private static double[] axisFit(Pairs pairs, double c, boolean xAxis) {
        double wsum = 0, ma = 0, mb = 0;
        for (int i = 0; i < pairs.from.size(); i++) {
            double w = pairs.weights.get(i);
            ma += w * ((xAxis ? pairs.from.get(i).x() : pairs.from.get(i).y()) - c);
            mb += w * ((xAxis ? pairs.to.get(i).x() : pairs.to.get(i).y()) - c);
            wsum += w;
        }
        ma /= wsum;
        mb /= wsum;
        double cov = 0, var = 0;
        for (int i = 0; i < pairs.from.size(); i++) {
            double w = pairs.weights.get(i);
            double a = (xAxis ? pairs.from.get(i).x() : pairs.from.get(i).y()) - c - ma;
            double b = (xAxis ? pairs.to.get(i).x() : pairs.to.get(i).y()) - c - mb;
            cov += w * a * b;
            var += w * a * a;
        }
        double s = var < 1e-9 ? 1 : cov / var;
        return new double[] {s, mb - s * ma, ma};
    }

    private record Pairs(List<Point> from, List<Point> to, List<Double> weights) {}

    private static double sigma(int it, int iterations, double sigmaStart, double sigmaEnd) {
        double t = iterations == 1 ? 1 : it / (double) (iterations - 1);
        return sigmaStart * Math.pow(sigmaEnd / sigmaStart, t);
    }

    /** Forward pairs (moved point → nearest target) and, if given, reverse pairs (target → nearest moved point). */
    private static Pairs pairs(List<Point> moving, PointMap current, PointIndex target, double pairDistance, double sigma,
                               PointIndex reverseTarget, double reverseRadius) {
        List<Point> from = new ArrayList<>(), to = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        List<Point> movedNow = current.apply(moving);
        for (int i = 0; i < moving.size(); i++) {
            Point q = movedNow.get(i);
            int n = target.nearest(q, pairDistance);
            if (n >= 0) {
                from.add(moving.get(i));
                to.add(target.points().get(n));
                weights.add(weight(PointIndex.distance(q, target.points().get(n)), sigma));
            }
        }
        if (reverseTarget != null && reverseRadius > 0) {
            PointIndex movedIndex = new PointIndex(movedNow, reverseRadius);
            for (Point tp : reverseTarget.points()) {
                int n = movedIndex.nearest(tp, reverseRadius);
                if (n >= 0) {
                    from.add(moving.get(n));
                    to.add(tp);
                    weights.add(weight(PointIndex.distance(tp, movedNow.get(n)), sigma));
                }
            }
        }
        return new Pairs(from, to, weights);
    }

    /**
     * Candidate score for the coarse search: how well the moved points lie on the target, averaged
     * with how well they cover {@code own} (the ink the part owns). One-sided scoring would favour
     * shrinking a part along its own lines (a 十 collapsed towards its crossing still lies on ink).
     */
    private static double twoSided(List<Point> probe, List<Point> own, PointMap t, PointIndex target, double pairDistance,
                                   double sigma) {
        double forward = quality(probe, t, target, pairDistance, sigma);
        if (own.isEmpty()) {
            return forward;
        }
        PointIndex moved = new PointIndex(t.apply(probe), pairDistance / 2);
        double sum = 0;
        for (Point p : own) {
            int n = moved.nearest(p, pairDistance);
            if (n >= 0) {
                sum += weight(PointIndex.distance(p, moved.points().get(n)), sigma);
            }
        }
        return (forward + sum / own.size()) / 2;
    }

    /**
     * A small cost for moving away from the model, so that among nearly equal candidates the search
     * prefers the model's own proportions; real differences (a 五 squashed to half height) gain far
     * more than this costs.
     */
    private static double prior(AxisTransform t) {
        return SCALE_PRIOR * (Math.abs(Math.log(t.sx())) + Math.abs(Math.log(t.sy())))
                + SHIFT_PRIOR * Math.hypot(t.tx(), t.ty());
    }

    private static final double SCALE_PRIOR = 0.15, SHIFT_PRIOR = 0.005;

    private static List<Point> subsample(List<Point> pts, int max) {
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

    /**
     * How well points lie on a target after a transform: the mean robust weight of each moved
     * point's distance to its nearest target point (0 when none is within {@code pairDistance}).
     * The same measure as {@link AxisResult#fit()}.
     *
     * @param moving points to move
     * @param t the transform
     * @param target index of the points to land on
     * @param pairDistance farthest a point counts as landing
     * @param sigma weight scale
     * @return fit quality in [0, 1]
     */
    public static double fitQuality(List<Point> moving, PointMap t, PointIndex target, double pairDistance, double sigma) {
        return moving.isEmpty() ? 0 : quality(moving, t, target, pairDistance, sigma);
    }

    private static double quality(List<Point> moving, PointMap t, PointIndex target, double pairDistance, double sigma) {
        double sum = 0;
        for (Point p : moving) {
            Point q = t.apply(p);
            int n = target.nearest(q, pairDistance);
            if (n >= 0) {
                sum += weight(PointIndex.distance(q, target.points().get(n)), sigma);
            }
        }
        return sum / moving.size();
    }

    private static double weight(double d, double sigma) {
        double u = d / sigma;
        return 1 / Math.pow(1 + u * u, 2);
    }

    private static SimilarityTransform clamp(SimilarityTransform t, Limits limits) {
        double maxScale = 1 + limits.maxScaleChange();
        double scale = Math.max(1 / maxScale, Math.min(maxScale, t.scale()));
        double maxRot = Math.toRadians(limits.maxRotationDegrees());
        double rotation = Math.max(-maxRot, Math.min(maxRot, t.rotation()));
        double tx = t.tx(), ty = t.ty(), len = Math.hypot(tx, ty);
        if (len > limits.maxShift()) {
            tx *= limits.maxShift() / len;
            ty *= limits.maxShift() / len;
        }
        return new SimilarityTransform(scale, rotation, tx, ty, t.cx(), t.cy());
    }
}
