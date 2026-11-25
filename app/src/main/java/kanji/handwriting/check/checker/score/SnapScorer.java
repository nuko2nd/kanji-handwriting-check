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
            // Each component is snapped as placed, and (if enabled) also stretched separately in
            // each direction first; the better result is kept. A stretch that fits the outline
            // better can still fit the strokes worse (学's dots squashed together), so the choice
            // is made on the strokes' scores.
            CompSnap best = snapComponent(shape, c, skel, claimed.clone(), allInk, compLimits, compPair, strokePair, tau,
                    claimRadius, sc, config, false);
            if (sc.componentMaxAspectChange() > 0 && shape.components().get(c).strokes().size() >= 2) {
                CompSnap stretched = snapComponent(shape, c, skel, claimed.clone(), allInk, compLimits, compPair, strokePair,
                        tau, claimRadius, sc, config, true);
                if (stretched.estimate() > best.estimate() + 1e-9) {
                    best = stretched;
                }
            }
            System.arraycopy(best.claimed(), 0, claimed, 0, claimed.length);
            placement[c] = best.placement();
            covered[c] = best.covered();
            snappedStrokes.set(c, best.strokes());
            strokeDef.set(c, best.defs());
            strokeCov.set(c, best.covs());
            strokePartPenalty.set(c, best.penalties());
            partScores.set(c, best.parts());
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
        if (sc.followRunOn()) {
            // Length is not judged: ink that just carries a stroke on past its end (士's long top
            // line for 土's short one) is that stroke drawn longer, not an extra mark.
            // A run-on is followed along the drawn line and stops at any other stroke: 日's middle
            // line drawn through its frame is still an error.
            boolean proportional = sc.runOnReach() <= 0;
            unsnapped.addAll(RunOn.continuations(snapped, ink, global, proportional ? RUN_ON_FRACTION : 0,
                    proportional ? MIN_RUN_ON_REACH : sc.runOnReach(),
                    sc.runOnMaxTurnDegrees()));
        }
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
                base.skeletonComponent(), base.skeletonStray(), covered, base.strayInk(), shape);
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
        return snapStroke(stroke, chain, ink, ownInk, sc, pairDistance, tau, sc.strokeMaxShift());
    }

    private static SnapResult snapStroke(ReferenceShape.Stroke stroke, List<PointMap> chain, PointIndex ink,
                                         PointIndex ownInk, SnapConfig sc, double pairDistance, double tau, double maxShift) {
        List<Point> moved = apply(chain, stroke.samples());
        List<Point> path = apply(chain, stroke.path());
        Point mid = midpoint(path);
        boolean locked = Polylines.length(path) < sc.minShapeLength();
        BoundedIcp.Limits limits = locked
                ? BoundedIcp.Limits.shiftOnly(maxShift)
                : new BoundedIcp.Limits(maxShift, sc.strokeMaxRotationDegrees(), sc.strokeMaxScaleChange());
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

    /** A run-on may reach this fraction of its stroke's length past the end... */
    private static final double RUN_ON_FRACTION = 0.8;
    /** ...or at least this many reference units, however short the stroke. */
    private static final double MIN_RUN_ON_REACH = 30;

    /** One component's snap: what {@link #score} records for it, and the ink claimed afterwards. */
    private record CompSnap(Deformation placement, List<ReferenceShape.Stroke> strokes, List<Deformation> defs,
                            double[] covs, double[] penalties, List<PartScore> parts, boolean[][] covered, boolean[] claimed) {
        /** The component's score before precision and extra marks are known. */
        double estimate() {
            double min = 1;
            for (int s = 0; s < covs.length; s++) {
                min = Math.min(min, covs[s] * defs.get(s).score() * penalties[s]);
            }
            return min * placement.score();
        }
    }

    /**
     * Snaps component {@code c}: places it, optionally stretches it in each direction
     * ({@code stretch}), snaps its parts and then each stroke, claiming ink in {@code claimed}
     * (which it changes).
     */
    private static CompSnap snapComponent(ReferenceShape shape, int c, List<Point> skel, boolean[] claimed, PointIndex allInk,
                                          BoundedIcp.Limits compLimits, double compPair, double strokePair, double tau,
                                          double claimRadius, SnapConfig sc, CheckerConfig config, boolean stretch) {
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
        double[] stretchFactor = new double[ns];
        java.util.Arrays.fill(stretchFactor, 1);
        if (stretch) {
            // Stretch the placed component separately in each direction (曜's 日 drawn tall
            // and narrow), pulled outward only by ink nearer this component than any other.
            List<Point> placed = tc.apply(pts);
            Point pc = centroid(placed);
            PointIndex own = new PointIndex(ownInk(shape, c, available, compPair), tau);
            // A side radical drawn narrow (扌, 亻: horizontals far shorter than KanjiVG's, verticals
            // full height), or a top or bottom one drawn flat, is ordinary handwriting: along its
            // arrangement it may change much more, and is scored leniently.
            int axis = arrangementAxis(comp.position());
            double maxChange = axis < 0 ? sc.componentMaxAspectChange()
                    : Math.max(sc.componentMaxAspectChange(), ALONG_MAX_STRETCH);
            BoundedIcp.AxisResult ar = BoundedIcp.searchAxes(placed, available, pc.x(), pc.y(), sc.partMaxShift() / 2,
                    maxChange, compPair, compPair / 2, tau / 2, sc.iterations(), own, tau / 2);
            AxisTransform ta = ar.transform();
            // Otherwise scored like the component's size, not a part's: stretching a whole
            // component changes its proportions, and must not quietly absorb one stroke drawn too
            // long (士's top line) or too short.
            Deformation d;
            AxisTransform judged; // the part of the stretch that stroke lengths are judged without
            if (axis < 0) {
                d = Deformation.of(ta.tx(), ta.ty(), 0, ta.dominantScale(), sc.partShift(), sc.partRotation(),
                        sc.componentSize());
                judged = ta;
            } else {
                double along = axis == 0 ? ta.sx() : ta.sy(), across = axis == 0 ? ta.sy() : ta.sx();
                double alongScore = Deformation.of(0, 0, 0, along, ANY_SHIFT, ANY_ROTATION, ALONG_STRETCH).score();
                Deformation crossD = Deformation.of(ta.tx(), ta.ty(), 0, across, sc.partShift(), sc.partRotation(),
                        sc.componentSize());
                double worst = Math.abs(Math.log(along)) > Math.abs(Math.log(across)) ? along : across;
                d = new Deformation(ta.tx(), ta.ty(), 0, worst, alongScore * crossD.score());
                // Lengths are judged after narrowing (a narrow 扌's horizontal is not too short),
                // but not after a stretch across the arrangement.
                judged = axis == 0 ? new AxisTransform(1, ta.sy(), 0, 0, ta.cx(), ta.cy())
                        : new AxisTransform(ta.sx(), 1, 0, 0, ta.cx(), ta.cy());
            }
            parts.add(new PartScore(comp.label(), d));
            for (int s = 0; s < ns; s++) {
                // How much the stretch alone lengthens this stroke: its length is judged against
                // the unstretched component, so a stretch cannot hide a stroke drawn too long.
                List<Point> path = apply(state[s].chain, comp.strokes().get(s).path());
                double before = Polylines.length(path);
                stretchFactor[s] = before > 1e-9 ? Polylines.length(judged.apply(path)) / before : 1;
                state[s].chain.add(ta);
                state[s].partPenalty *= d.score();
            }
        }
        snapParts(comp, comp.parts(), state, parts, available, compLimits, compPair, tau, sc);

        ReferenceShape.Stroke[] outStrokes = new ReferenceShape.Stroke[ns];
        Deformation[] defs = new Deformation[ns];
        boolean[] locked = new boolean[ns];
        double[] covs = new double[ns];
        double[] ownShift = new double[ns * 2];
        boolean[][] coveredC = new boolean[ns][];

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
            List<Point> snappedSamples = r.transform().apply(apply(state[s].chain, stroke.samples()));
            // Coverage against the ink available to this stroke, then claim its ink.
            coveredC[s] = new boolean[snappedSamples.size()];
            covs[s] = coverage(snappedSamples, avail, config, coveredC[s]);
            if (covs[s] < config.missingCoverage()) {
                // Next to nothing to land on: its ink was taken by a neighbour that snapped first
                // (灬's four dots drawn closer together than the model's), or lies a little beyond
                // the usual reach. Look farther, among the ink still free; the farther move is
                // still scored.
                double reach = RESCUE_REACH * sc.strokeMaxShift();
                SnapResult far = snapStroke(stroke, state[s].chain, avail, avail, sc, reach + tau, tau, reach);
                List<Point> farSamples = far.transform().apply(apply(state[s].chain, stroke.samples()));
                boolean[] farCovered = new boolean[farSamples.size()];
                double farCov = coverage(farSamples, avail, config, farCovered);
                if (farCov >= config.minStrokeCoverage()) {
                    r = far;
                    snappedSamples = farSamples;
                    coveredC[s] = farCovered;
                    covs[s] = farCov;
                }
            }
            SimilarityTransform ts = r.transform();
            List<Point> snappedPath = ts.apply(apply(state[s].chain, stroke.path()));
            outStrokes[s] = new ReferenceShape.Stroke(stroke.index(), stroke.kvgType(), snappedPath, snappedSamples);
            locked[s] = r.shapeLocked();
            ownShift[2 * s] = ts.tx();
            ownShift[2 * s + 1] = ts.ty();
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
            defs[s] = new Deformation(0, 0, rotation, locked[s] ? 1 : ts.scale() * stretchFactor[s], 1);
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
        // A single loose stroke (本's short line) is a stroke, not a radical: its placement is
        // always judged with the standard allowances, even when radical placement is free.
        // A single loose stroke (本's short line, 末's top line) is a stroke, not a radical: it
        // may not grow or shrink as freely as a radical, or a stroke drawn too long would simply
        // be scaled up to fit.
        SnapConfig placementRules = ns == 1 ? SINGLE_STROKE_RULES : sc;
        // A lone dot (玉's, 太's) is placed more loosely in handwriting than a line is: it may
        // sit anywhere near its spot, and only a dot clearly elsewhere (outside the frame,
        // up against another stroke) is wrong.
        Deformation.Allowance shiftRule = ns == 1 && isDot(comp.strokes().get(0))
                ? new Deformation.Allowance(DOT_FREE_SHIFT, placementRules.componentShift().sigma())
                : placementRules.componentShift();
        Deformation placementC = arrangementAxis(comp.position()) < 0 || ns == 1
                ? Deformation.of(mx, my, tc.rotationDegrees(), tc.scale(), shiftRule,
                        placementRules.componentRotation(), placementRules.componentSize())
                : Deformation.of(mx, my, arrangementAxis(comp.position()), tc.rotationDegrees(), tc.scale(), shiftRule,
                        placementRules.componentCrossShift(), placementRules.componentRotation(),
                        placementRules.componentSize());

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
        return new CompSnap(placementC, List.of(outStrokes), List.of(defs), covs, penalties, List.copyOf(parts), coveredC,
                claimed);
    }

    /**
     * The available ink nearer to component {@code c}'s model strokes than to any other
     * component's (and within {@code pair} of them).
     */
    private static List<Point> ownInk(ReferenceShape shape, int c, PointIndex available, double pair) {
        List<Point> refPts = new ArrayList<>();
        List<Integer> refOwner = new ArrayList<>();
        for (int k = 0; k < shape.components().size(); k++) {
            for (Point p : samples(shape.components().get(k).strokes())) {
                refPts.add(p);
                refOwner.add(k);
            }
        }
        PointIndex refIndex = new PointIndex(refPts, pair / 2);
        List<Point> own = new ArrayList<>();
        for (Point p : available.points()) {
            int n = refIndex.nearest(p, pair);
            if (n >= 0 && refOwner.get(n) == c) {
                own.add(p);
            }
        }
        return own;
    }

    /** A stroke that finds next to no ink searches again this many times as far. */
    private static final double RESCUE_REACH = 2.5;

    /** Mean credit of the samples against the ink; marks each sample covered (credit at least ½). */
    private static double coverage(List<Point> samples, PointIndex ink, CheckerConfig config, boolean[] covered) {
        double credit = 0;
        for (int k = 0; k < samples.size(); k++) {
            int n = ink.nearest(samples.get(k), config.creditZeroDistance());
            double cr = n < 0 ? 0 : Scorer.credit(PointIndex.distance(samples.get(k), ink.points().get(n)), config);
            covered[k] = cr >= 0.5;
            credit += cr;
        }
        return samples.isEmpty() ? 0 : credit / samples.size();
    }

    /**
     * The axis a component is arranged along: 0 (x) for KanjiVG position left or right, 1 (y) for
     * top or bottom, -1 for anything else (enclosures, 繞, or no position).
     */
    static int arrangementAxis(String position) {
        if (position == null) {
            return -1;
        }
        return switch (position) {
            case "left", "right" -> 0;
            case "top", "bottom" -> 1;
            default -> -1;
        };
    }

    /** Largest stretch either way along a side, top or bottom radical's arrangement. */
    private static final double ALONG_MAX_STRETCH = 0.7;
    /** Free stretch and penalty scale along a radical's arrangement. */
    private static final Deformation.Allowance ALONG_STRETCH = new Deformation.Allowance(0.6, 0.3);
    /** Allowances that ignore shift and rotation (for scoring a scale alone). */
    private static final Deformation.Allowance ANY_SHIFT = new Deformation.Allowance(1e9, 1),
            ANY_ROTATION = new Deformation.Allowance(1e9, 1);

    /** Placement allowances for a component of one stroke, whatever the grading level. */
    private static final SnapConfig SINGLE_STROKE_RULES = SnapConfig.defaults()
            .withComponentAllowances(new Deformation.Allowance(4, 4), new Deformation.Allowance(6, 10),
                    new Deformation.Allowance(0.1, 0.12));

    /** A lone dot may sit this many reference units from its model spot without penalty. */
    private static final double DOT_FREE_SHIFT = 10;

    /** Whether a stroke is a dot (KanjiVG stroke type ㇔). */
    private static boolean isDot(ReferenceShape.Stroke stroke) {
        return stroke.kvgType() != null && stroke.kvgType().startsWith("㇔");
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
