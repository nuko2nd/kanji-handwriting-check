package kanji.handwriting.check.checker.score;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.checker.SnapConfig;
import kanji.handwriting.check.checker.align.AxisTransform;
import kanji.handwriting.check.checker.align.BoundedIcp;
import kanji.handwriting.check.checker.align.PointMap;
import kanji.handwriting.check.checker.align.SimilarityTransform;
import kanji.handwriting.check.checker.geometry.PointIndex;
import kanji.handwriting.check.checker.geometry.Polylines;
import kanji.handwriting.check.checker.image.InkImage;
import kanji.handwriting.check.checker.reference.ReferenceShape;
import kanji.handwriting.check.model.Point;

/**
 * Snap scoring: instead of comparing the drawing with the reference where it stands, each
 * reference component, then each of its parts, then each stroke is snapped onto the nearest ink,
 * and the score reflects how far they had to move, turn and stretch, and how well they fit
 * afterwards.
 *
 * <ol>
 *   <li><b>Order.</b> Components are processed best-fitting first (each fitted on its own against
 *       all ink), and strokes within a component likewise.
 *   <li><b>Component snap.</b> A bounded similarity fit (see {@link BoundedIcp}) of the component's
 *       sample points onto the unclaimed ink, about the component's centroid.
 *   <li><b>Part snap.</b> Each part of the component with two or more strokes (KanjiVG's
 *       sub-components, e.g. 五 and 口 in 吾) is fitted again as a unit, then its own parts, and so
 *       on down the tree. This matches structure before single strokes, so a part drawn in
 *       different proportions from KanjiVG's (a big 口 under a squashed 五) does not make strokes
 *       snap onto a neighbouring part's ink. Each part's movement, relative to its parent, is a
 *       {@link PartScore} with lenient allowances ({@link SnapConfig#partShift()}), and its score
 *       multiplies the scores of the part's strokes.
 *   <li><b>Stroke snap.</b> Each stroke, moved with its component and parts, is fitted again about
 *       its own midpoint onto the ink still unclaimed. Strokes shorter than
 *       {@link SnapConfig#minShapeLength()} may only shift; their direction is then read from the
 *       ink they claim and compared with the reference, so a dot written the wrong way (／ for ＼)
 *       is caught.
 *   <li><b>Claiming.</b> After a stroke is snapped, ink within τ/2 of it is claimed and is no
 *       longer available to later strokes. A stroke therefore cannot snap onto a neighbour's ink
 *       (the three lines of 三, a missing dot next to 大), while strokes that cross still find
 *       their own ink at the crossing.
 *   <li><b>Placement.</b> A component's placement is the weighted median of its strokes' total
 *       displacements, so the fit's arbitrary split of movement between levels does not let free
 *       allowances add up, and one stroke out of place does not drag its component along.
 *   <li><b>Stroke score</b> = coverage (mean credit of the snapped stroke's points against the ink
 *       that was available to it) × deformation score × the scores of the parts it belongs to.
 *   <li><b>Component score</b> = F1 of its lowest stroke score and its precision (computed by
 *       {@link Scorer} against the snapped strokes) × placement score, minus the extra-mark penalty.
 * </ol>
 */
public final class SnapScorer {
    private SnapScorer() {}

    /** Per-stroke state while snapping one component. */
    private static final class StrokeState {
        final List<PointMap> chain = new ArrayList<>(); // component, then parts, outermost first
        double partPenalty = 1;
        boolean inPart;
    }

    /**
     * Scores a drawing by snapping.
     *
     * @param shape the reference
     * @param ink the preprocessed drawing
     * @param global alignment from drawing space into reference space
     * @param config tolerances, penalties and {@link CheckerConfig#snap()}
     * @return per-component scores, stray marks, and overlay details (against the snapped shape)
     */
    public static ScoreReport score(ReferenceShape shape, InkImage ink, SimilarityTransform global, CheckerConfig config) {
        SnapConfig sc = config.snap();
        double tau = config.tolerance(), claimRadius = tau / 2;
        List<Point> skel = global.apply(ink.skeletonPoints());
        boolean[] claimed = new boolean[skel.size()];
        PointIndex allInk = new PointIndex(skel, tau);
        int nc = shape.components().size();

        BoundedIcp.Limits compLimits = new BoundedIcp.Limits(sc.componentMaxShift(), sc.componentMaxRotationDegrees(),
                sc.componentMaxScaleChange());
        double compPair = sc.componentMaxShift() + tau, strokePair = sc.strokeMaxShift() + tau;

        // Order components by how well they fit on their own.
        List<Integer> compOrder = new ArrayList<>();
        double[] compFit = new double[nc];
        for (int c = 0; c < nc; c++) {
            compOrder.add(c);
            ReferenceShape.Component comp = shape.components().get(c);
            List<Point> pts = samples(comp.strokes());
            Point ctr = centroid(pts);
            compFit[c] = fitGroup(pts, ctr, allInk, groupLimits(comp.strokes().size(), pts, compLimits, sc), compPair, tau, sc)
                    .fit();
        }
        compOrder.sort(Comparator.comparingDouble(c -> -compFit[c]));

        Deformation[] placement = new Deformation[nc];
        List<List<ReferenceShape.Stroke>> snappedStrokes = new ArrayList<>();
        List<List<Deformation>> strokeDef = new ArrayList<>();
        List<double[]> strokeCov = new ArrayList<>();
        List<double[]> strokePartPenalty = new ArrayList<>();
        List<List<PartScore>> partScores = new ArrayList<>();
        boolean[][][] covered = new boolean[nc][][];
        for (int c = 0; c < nc; c++) {
            snappedStrokes.add(null);
            strokeDef.add(null);
            strokeCov.add(null);
            strokePartPenalty.add(null);
            partScores.add(null);
        }

        for (int c : compOrder) {
            ReferenceShape.Component comp = shape.components().get(c);
            int ns = comp.strokes().size();
            List<Point> pts = samples(comp.strokes());
            Point ctr = centroid(pts);
            PointIndex available = unclaimed(skel, claimed, tau);
            SimilarityTransform tc = fitGroup(pts, ctr, available, groupLimits(ns, pts, compLimits, sc), compPair, tau, sc)
                    .transform();

            StrokeState[] state = new StrokeState[ns];
            for (int s = 0; s < ns; s++) {
                state[s] = new StrokeState();
                state[s].chain.add(tc);
            }
            List<PartScore> parts = new ArrayList<>();
            snapParts(comp, comp.parts(), state, parts, available, compLimits, compPair, tau, sc);

            ReferenceShape.Stroke[] outStrokes = new ReferenceShape.Stroke[ns];
            Deformation[] defs = new Deformation[ns];
            boolean[] locked = new boolean[ns];
            double[] covs = new double[ns];
            double[] ownShift = new double[ns * 2];
            covered[c] = new boolean[ns][];

            // Order this component's strokes by independent fit against the ink still available.
            List<Integer> strokeOrder = new ArrayList<>();
            double[] strokeFit = new double[ns];
            for (int s = 0; s < ns; s++) {
                strokeOrder.add(s);
                strokeFit[s] = snapStroke(comp.strokes().get(s), state[s].chain, allInk, available, sc, strokePair, tau).fit();
            }
            strokeOrder.sort(Comparator.comparingDouble(s -> -strokeFit[s]));

            for (int s : strokeOrder) {
                ReferenceShape.Stroke stroke = comp.strokes().get(s);
                PointIndex avail = unclaimed(skel, claimed, tau);
                SnapResult r = snapStroke(stroke, state[s].chain, allInk, avail, sc, strokePair, tau);
                SimilarityTransform ts = r.transform();
                List<Point> snappedSamples = ts.apply(apply(state[s].chain, stroke.samples()));
                List<Point> snappedPath = ts.apply(apply(state[s].chain, stroke.path()));
                outStrokes[s] = new ReferenceShape.Stroke(stroke.index(), stroke.kvgType(), snappedPath, snappedSamples);
                locked[s] = r.shapeLocked();
                ownShift[2 * s] = ts.tx();
                ownShift[2 * s + 1] = ts.ty();

                // Coverage against the ink available to this stroke, then claim its ink.
                covered[c][s] = new boolean[snappedSamples.size()];
                double credit = 0;
                for (int k = 0; k < snappedSamples.size(); k++) {
                    int n = avail.nearest(snappedSamples.get(k), config.creditZeroDistance());
                    double cr = n < 0 ? 0 : Scorer.credit(PointIndex.distance(snappedSamples.get(k), avail.points().get(n)), config);
                    covered[c][s][k] = cr >= 0.5;
                    credit += cr;
                }
                covs[s] = credit / snappedSamples.size();
                PointIndex strokeIndex = new PointIndex(snappedSamples, claimRadius);
                List<Point> claimedNow = new ArrayList<>();
                for (int i = 0; i < skel.size(); i++) {
                    if (!claimed[i] && strokeIndex.anyWithin(skel.get(i), claimRadius)) {
                        claimed[i] = true;
                        claimedNow.add(skel.get(i));
                    }
                }
                // A short stroke was only shifted: read its direction from the ink it landed on.
                double rotation = locked[s] ? angleBetween(snappedSamples, claimedNow) : ts.rotationDegrees();
                defs[s] = new Deformation(0, 0, rotation, locked[s] ? 1 : ts.scale(), 1);
            }

            // Placement: weighted median of the strokes' total displacements (see class docs).
            // Total displacements, including any part movement: parts drawn in different proportions
            // move in different directions (五 up, 口 down) and leave the median small, while a whole
            // component drawn out of place moves every stroke the same way.
            double[][] total = new double[ns][];
            double[] w = new double[ns];
            for (int s = 0; s < ns; s++) {
                Point from = midpoint(comp.strokes().get(s).path()), to = midpoint(outStrokes[s].path());
                total[s] = new double[] {to.x() - from.x(), to.y() - from.y()};
                w[s] = Polylines.length(comp.strokes().get(s).path()) * covs[s];
            }
            double mx = weightedMedian(total, w, 0, tc.tx()), my = weightedMedian(total, w, 1, tc.ty());
            placement[c] = Deformation.of(mx, my, tc.rotationDegrees(), tc.scale(), sc.componentShift(),
                    sc.componentRotation(), sc.componentSize());

            // A stroke's own movement: relative to its part if it is in one (the part's movement is
            // scored separately), otherwise relative to the component's placement.
            double[] penalties = new double[ns];
            for (int s = 0; s < ns; s++) {
                double dx = state[s].inPart ? ownShift[2 * s] : total[s][0] - mx;
                double dy = state[s].inPart ? ownShift[2 * s + 1] : total[s][1] - my;
                Deformation d = defs[s];
                defs[s] = locked[s]
                        ? Deformation.of(dx, dy, d.rotationDegrees(), 1, sc.shortStrokeShift(), sc.shortStrokeRotation(),
                                sc.strokeSize())
                        : Deformation.of(dx, dy, d.rotationDegrees(), d.scale(), sc.strokeShift(), sc.strokeRotation(),
                                sc.strokeSize());
                penalties[s] = state[s].partPenalty;
            }
            snappedStrokes.set(c, List.of(outStrokes));
            strokeDef.set(c, List.of(defs));
            strokeCov.set(c, covs);
            strokePartPenalty.set(c, penalties);
            partScores.set(c, List.copyOf(parts));
        }

        List<ReferenceShape.Component> snappedComps = new ArrayList<>();
        for (int c = 0; c < nc; c++) {
            ReferenceShape.Component comp = shape.components().get(c);
            snappedComps.add(new ReferenceShape.Component(comp.label(), comp.position(), snappedStrokes.get(c), comp.parts()));
        }
        ReferenceShape snapped = new ReferenceShape(shape.kanji(), shape.sourceKanji(), shape.variant(), List.copyOf(snappedComps));

        // Precision, assignment and extra marks against the snapped strokes.
        List<Point> unsnapped = new ArrayList<>();
        shape.components().forEach(cmp -> unsnapped.addAll(samples(cmp.strokes())));
        ScoreReport base = Scorer.score(snapped, ink, global, config, unsnapped);

        List<ComponentScore> result = new ArrayList<>();
        for (int c = 0; c < nc; c++) {
            ComponentScore b = base.components().get(c);
            List<ComponentScore.StrokeScore> strokes = new ArrayList<>();
            double min = 1;
            for (int s = 0; s < snapped.components().get(c).strokes().size(); s++) {
                ReferenceShape.Stroke st = snapped.components().get(c).strokes().get(s);
                double cov = strokeCov.get(c)[s];
                Deformation d = strokeDef.get(c).get(s);
                double score = cov * d.score() * strokePartPenalty.get(c)[s];
                strokes.add(new ComponentScore.StrokeScore(st.index(), st.kvgType(), cov, d, score));
                min = Math.min(min, score);
            }
            double p = b.precision();
            double f1 = min + p == 0 ? 0 : 2 * min * p / (min + p);
            double score = Math.max(0, f1 * placement[c].score() - b.extraMarks() * config.strayPenalty());
            result.add(new ComponentScore(b.label(), b.position(), min, p, f1, score, List.copyOf(strokes),
                    b.assignedPoints(), b.strayPoints(), b.extraMarks(), placement[c], partScores.get(c)));
        }
        return new ScoreReport(List.copyOf(result), base.strayMarks(), base.strayPoints(), snapped, base.alignedSkeleton(),
                base.skeletonComponent(), base.skeletonStray(), covered, base.strayInk());
    }

    /**
     * Snaps each multi-stroke part as a unit (best-fitting first), records its movement, applies it
     * to its strokes, and recurses into its own parts.
     */
    private static void snapParts(ReferenceShape.Component comp, List<ReferenceShape.Part> parts, StrokeState[] state,
                                  List<PartScore> out, PointIndex ink, BoundedIcp.Limits limits, double pair, double tau,
                                  SnapConfig sc) {
        // Only parts of two or more strokes, and not a part that is the whole component (KanjiVG
        // sometimes wraps all of a component's strokes in one unnamed group): fitting it again adds
        // nothing and only lets it drift.
        List<ReferenceShape.Part> multi = parts.stream()
                .filter(p -> p.strokes().size() >= 2 && p.strokes().size() < comp.strokes().size()).toList();
        if (multi.isEmpty()) {
            return;
        }
        // Sibling parts claim ink as they are snapped, like strokes do, so that where two parts
        // compete for the same ink (五's bottom line next to the top of a large 口) one keeps it and
        // the other is pushed onto its own ink. Which one should keep it is decided by trying every
        // order (there are rarely more than three siblings) and keeping the one with the best total
        // fit: the right order lets both parts fit; the wrong one leaves the 口 a side short.
        List<int[]> orders = orders(multi.size());
        @SuppressWarnings("unchecked")
        List<Point>[] samples = new List[multi.size()];
        for (int i = 0; i < multi.size(); i++) {
            samples[i] = partSamples(comp, multi.get(i), state);
        }
        int[] owner = owners(comp, multi, state, ink, pair);
        PartFit[] best = null;
        double bestTotal = -1;
        for (int[] order : orders) {
            PartFit[] fits = fitInOrder(order, samples, ink, owner, limits, pair, tau, sc);
            double total = 0;
            for (PartFit f : fits) {
                total += f.fit;
            }
            if (total > bestTotal + 1e-9) {
                bestTotal = total;
                best = fits;
            }
        }
        for (int i = 0; i < multi.size(); i++) {
            ReferenceShape.Part part = multi.get(i);
            AxisTransform tp = best[i].transform;
            PointIndex partInk = best[i].ink;
            Deformation d = Deformation.of(tp.tx(), tp.ty(), 0, tp.dominantScale(), sc.partShift(),
                    sc.partRotation(), sc.partSize());
            out.add(new PartScore(part.label(), d));
            for (int s : part.strokes()) {
                state[s].chain.add(tp);
                state[s].partPenalty *= d.score();
                state[s].inPart = true;
            }
            snapParts(comp, part.parts(), state, out, partInk, limits, pair, tau, sc);
        }
    }

    /** A sibling part's fit: its transform, quality, and the ink it was fitted against. */
    private record PartFit(AxisTransform transform, double fit, PointIndex ink) {}

    /** Most sibling parts whose snapping orders are all tried; beyond this, best-fitting first. */
    private static final int MAX_PERMUTED_PARTS = 4;

    /**
     * For each ink point, which of the sibling parts owns it: the part with the nearest reference
     * point (in the parts' current positions), or -1 if that nearest point belongs to a stroke
     * outside these parts or nothing is within {@code pair}.
     */
    private static int[] owners(ReferenceShape.Component comp, List<ReferenceShape.Part> multi, StrokeState[] state,
                                PointIndex ink, double pair) {
        int[] partOf = new int[comp.strokes().size()];
        Arrays.fill(partOf, -1);
        for (int i = 0; i < multi.size(); i++) {
            for (int s : multi.get(i).strokes()) {
                partOf[s] = i;
            }
        }
        List<Point> refPts = new ArrayList<>();
        List<Integer> refOwner = new ArrayList<>();
        for (int s = 0; s < comp.strokes().size(); s++) {
            for (Point p : apply(state[s].chain, comp.strokes().get(s).samples())) {
                refPts.add(p);
                refOwner.add(partOf[s]);
            }
        }
        PointIndex refIndex = new PointIndex(refPts, pair / 2);
        int[] owner = new int[ink.points().size()];
        for (int k = 0; k < owner.length; k++) {
            int n = refIndex.nearest(ink.points().get(k), pair);
            owner[k] = n < 0 ? -1 : refOwner.get(n);
        }
        return owner;
    }

    /**
     * Fits sibling parts in the given order, each claiming the ink it lands on; results by part
     * index. A part is pulled outward (reverse pairs) only by ink it owns, so it cannot stretch over
     * a sibling's ink.
     */
    private static PartFit[] fitInOrder(int[] order, List<Point>[] samples, PointIndex ink, int[] owner,
                                        BoundedIcp.Limits limits, double pair, double tau, SnapConfig sc) {
        List<Point> inkPts = ink.points();
        boolean[] taken = new boolean[inkPts.size()];
        PartFit[] out = new PartFit[order.length];
        for (int i : order) {
            List<Point> free = new ArrayList<>(), own = new ArrayList<>();
            for (int k = 0; k < inkPts.size(); k++) {
                if (!taken[k]) {
                    free.add(inkPts.get(k));
                    if (owner[k] == i) {
                        own.add(inkPts.get(k));
                    }
                }
            }
            PointIndex partInk = new PointIndex(free, tau);
            BoundedIcp.AxisResult r = fitPart(samples[i], partInk, new PointIndex(own, tau), limits, pair, tau, sc);
            out[i] = new PartFit(r.transform(), r.fit(), partInk);
            PointIndex placed = new PointIndex(r.transform().apply(samples[i]), tau / 2);
            for (int k = 0; k < inkPts.size(); k++) {
                if (!taken[k] && placed.anyWithin(inkPts.get(k), tau / 2)) {
                    taken[k] = true;
                }
            }
        }
        return out;
    }

    /** All orderings of 0..n-1 if n is small, otherwise just the identity order. */
    private static List<int[]> orders(int n) {
        List<int[]> out = new ArrayList<>();
        int[] base = new int[n];
        for (int i = 0; i < n; i++) {
            base[i] = i;
        }
        if (n > MAX_PERMUTED_PARTS) {
            out.add(base);
            return out;
        }
        permute(base, 0, out);
        return out;
    }

    private static void permute(int[] a, int k, List<int[]> out) {
        if (k == a.length) {
            out.add(a.clone());
            return;
        }
        for (int i = k; i < a.length; i++) {
            int t = a[k];
            a[k] = a[i];
            a[i] = t;
            permute(a, k + 1, out);
            a[i] = a[k];
            a[k] = t;
        }
    }

    /**
     * Fits a part with separate horizontal and vertical scales (a part may be drawn flatter or
     * taller than the model), about its centroid; small parts may only shift.
     */
    private static BoundedIcp.AxisResult fitPart(List<Point> pts, PointIndex ink, PointIndex ownInk,
                                                 BoundedIcp.Limits limits, double pair, double tau, SnapConfig sc) {
        Point ctr = centroid(pts);
        double scaleChange = groupLimits(2, pts, limits, sc).maxScaleChange() == 0 ? 0 : sc.partMaxScaleChange();
        return BoundedIcp.searchAxes(pts, ink, ctr.x(), ctr.y(), sc.partMaxShift(), scaleChange, pair, pair / 2, tau / 2,
                sc.iterations(), ownInk, tau / 2);
    }

    private static List<Point> partSamples(ReferenceShape.Component comp, ReferenceShape.Part part, StrokeState[] state) {
        List<Point> pts = new ArrayList<>();
        for (int s : part.strokes()) {
            pts.addAll(apply(state[s].chain, comp.strokes().get(s).samples()));
        }
        return pts;
    }

    private static BoundedIcp.Result fitGroup(List<Point> pts, Point ctr, PointIndex ink, BoundedIcp.Limits limits,
                                              double pair, double tau, SnapConfig sc) {
        return BoundedIcp.fit(pts, ink, ctr.x(), ctr.y(), limits, pair, pair / 2, tau / 2, sc.iterations(), ink, tau / 2);
    }

    private record SnapResult(SimilarityTransform transform, double fit, boolean shapeLocked) {}

    /** Snaps one stroke (already moved by its component's and parts' transforms) onto the given ink. */
    private static SnapResult snapStroke(ReferenceShape.Stroke stroke, List<PointMap> chain, PointIndex ink,
                                         PointIndex ownInk, SnapConfig sc, double pairDistance, double tau) {
        List<Point> moved = apply(chain, stroke.samples());
        List<Point> path = apply(chain, stroke.path());
        Point mid = midpoint(path);
        boolean locked = Polylines.length(path) < sc.minShapeLength();
        BoundedIcp.Limits limits = locked
                ? BoundedIcp.Limits.shiftOnly(sc.strokeMaxShift())
                : new BoundedIcp.Limits(sc.strokeMaxShift(), sc.strokeMaxRotationDegrees(), sc.strokeMaxScaleChange());
        BoundedIcp.Result r = BoundedIcp.fit(moved, ink, mid.x(), mid.y(), limits, pairDistance, pairDistance / 2, tau / 2,
                sc.iterations(), ownInk, tau / 2);
        return new SnapResult(r.transform(), r.fit(), locked);
    }

    /**
     * Groups of one stroke, and small groups (a lone dot), may only shift: a single stroke's shape is
     * measured once, at the stroke level, rather than split between two levels whose free allowances
     * would add up.
     */
    private static BoundedIcp.Limits groupLimits(int strokeCount, List<Point> pts, BoundedIcp.Limits limits, SnapConfig sc) {
        if (strokeCount == 1) {
            return BoundedIcp.Limits.shiftOnly(limits.maxShift());
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Point p : pts) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return Math.max(maxX - minX, maxY - minY) < sc.minShapeLength()
                ? BoundedIcp.Limits.shiftOnly(limits.maxShift()) : limits;
    }

    /**
     * Signed angle, in degrees within (-90, 90], from the principal direction of the stroke's points
     * to that of the ink it claimed; 0 if the ink is too small to have a direction (fewer than 5
     * points or under 3 units across).
     */
    static double angleBetween(List<Point> stroke, List<Point> ink) {
        if (ink.size() < 5 || spread(ink) < 3) {
            return 0;
        }
        double diff = Math.toDegrees(direction(ink) - direction(stroke));
        while (diff > 90) {
            diff -= 180;
        }
        while (diff <= -90) {
            diff += 180;
        }
        return diff;
    }

    /** Principal axis angle (radians) of a point set, by PCA. */
    private static double direction(List<Point> pts) {
        Point c = centroid(pts);
        double sxx = 0, syy = 0, sxy = 0;
        for (Point p : pts) {
            double dx = p.x() - c.x(), dy = p.y() - c.y();
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        return 0.5 * Math.atan2(2 * sxy, sxx - syy);
    }

    /** Largest distance between a point and the centroid, times two: a rough diameter. */
    private static double spread(List<Point> pts) {
        Point c = centroid(pts);
        double max = 0;
        for (Point p : pts) {
            max = Math.max(max, PointIndex.distance(p, c));
        }
        return 2 * max;
    }

    private static List<Point> apply(List<PointMap> chain, List<Point> pts) {
        List<Point> out = pts;
        for (PointMap t : chain) {
            out = t.apply(out);
        }
        return out;
    }

    /** Weighted median of values[i][axis]; {@code fallback} if all weights are zero. */
    private static double weightedMedian(double[][] values, double[] weights, int axis, double fallback) {
        Integer[] order = new Integer[values.length];
        double total = 0;
        for (int i = 0; i < values.length; i++) {
            order[i] = i;
            total += weights[i];
        }
        if (total <= 0) {
            return fallback;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> values[i][axis]));
        double run = 0;
        for (int i : order) {
            run += weights[i];
            if (run >= total / 2) {
                return values[i][axis];
            }
        }
        return values[order[order.length - 1]][axis];
    }

    private static Point midpoint(List<Point> path) {
        List<Point> half = Polylines.truncate(path, 0.5);
        return half.get(half.size() - 1);
    }

    private static PointIndex unclaimed(List<Point> skel, boolean[] claimed, double cell) {
        List<Point> free = new ArrayList<>();
        for (int i = 0; i < skel.size(); i++) {
            if (!claimed[i]) {
                free.add(skel.get(i));
            }
        }
        return new PointIndex(free, cell);
    }

    private static List<Point> samples(List<ReferenceShape.Stroke> strokes) {
        List<Point> pts = new ArrayList<>();
        strokes.forEach(s -> pts.addAll(s.samples()));
        return pts;
    }

    private static Point centroid(List<Point> pts) {
        double x = 0, y = 0;
        for (Point p : pts) {
            x += p.x();
            y += p.y();
        }
        return new Point(x / pts.size(), y / pts.size());
    }
}
