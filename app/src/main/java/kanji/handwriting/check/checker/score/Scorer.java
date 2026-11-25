package kanji.handwriting.check.checker.score;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.checker.align.SimilarityTransform;
import kanji.handwriting.check.checker.geometry.PointIndex;
import kanji.handwriting.check.checker.image.InkImage;
import kanji.handwriting.check.checker.reference.ReferenceShape;
import kanji.handwriting.check.model.Point;

/**
 * Scores an aligned drawing against a reference, component by component, using only point-set
 * distances. Self-contained so it can later be replaced by another detector.
 *
 * <ol>
 *   <li><b>Assignment</b>: each drawing skeleton point goes to the component owning its nearest
 *       reference point. Points farther than the stray distance from every stroke are stray, and
 *       still count against that nearest component.
 *   <li><b>Stroke coverage</b> (missing ink): the mean {@link #credit} of a stroke's sample points,
 *       by distance to the nearest drawing point. Credit is 1 within τ and falls linearly to 0 at
 *       {@code creditZeroDistance}, so ink slightly off the reference still earns partial credit.
 *       <b>Component coverage</b> is the minimum over its strokes.
 *   <li><b>Precision</b> (extra ink): the mean credit of the component's assigned points, by
 *       distance to the nearest reference point.
 *   <li><b>F1</b> of coverage and precision.
 *   <li><b>Extra marks</b>: stray ink pixels are grouped into connected patches. A skeleton reduces
 *       a dot to a pixel or two, so a stray dot would hardly move precision. Patches of at least
 *       {@code strayMinArea} are therefore reported and each costs its nearest component
 *       {@code strayPenalty}.
 * </ol>
 */
public final class Scorer {
    private static final double FAR = 1000;

    private Scorer() {}

    /**
     * Scores a drawing.
     *
     * @param shape the reference
     * @param ink the preprocessed drawing
     * @param transform alignment from drawing space into reference space
     * @param config tolerances and penalties
     * @return per-component scores, stray marks, and overlay details
     */
    public static ScoreReport score(ReferenceShape shape, InkImage ink, SimilarityTransform transform,
                                    CheckerConfig config) {
        return score(shape, ink, transform, config, List.of());
    }

    /**
     * Scores a drawing, with extra points that also count as explaining ink when looking for stray
     * marks. Snap scoring passes the unsnapped reference: a stroke that snapped slightly wrong
     * must not turn its own ink into an "extra mark", while a real extra line is far from both.
     *
     * @param shape the reference
     * @param ink the preprocessed drawing
     * @param transform alignment from drawing space into reference space
     * @param config tolerances and penalties
     * @param alsoExplains further points; ink within the stray distance of these is not stray
     * @return per-component scores, stray marks, and overlay details
     */
    public static ScoreReport score(ReferenceShape shape, InkImage ink, SimilarityTransform transform,
                                    CheckerConfig config, List<Point> alsoExplains) {
        double tau = config.tolerance(), strayDist = config.strayDistance();
        PointIndex extraIndex = new PointIndex(alsoExplains, tau);
        List<ReferenceShape.Component> comps = shape.components();

        // All reference sample points, remembering which component owns each.
        List<Point> refPts = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        for (int c = 0; c < comps.size(); c++) {
            for (ReferenceShape.Stroke s : comps.get(c).strokes()) {
                for (Point p : s.samples()) {
                    refPts.add(p);
                    owner.add(c);
                }
            }
        }
        PointIndex refIndex = new PointIndex(refPts, tau);

        // 1. Assignment of drawing skeleton points.
        List<Point> skel = transform.apply(ink.skeletonPoints());
        int[] assigned = new int[skel.size()];
        boolean[] stray = new boolean[skel.size()];
        int[] assignedCount = new int[comps.size()], strayCount = new int[comps.size()];
        double[] onTarget = new double[comps.size()]; // summed credit of assigned points
        int strayTotal = 0;
        for (int i = 0; i < skel.size(); i++) {
            Point p = skel.get(i);
            int n = refIndex.nearest(p, FAR);
            int c = owner.get(n);
            double d = PointIndex.distance(p, refPts.get(n));
            assigned[i] = c;
            assignedCount[c]++;
            onTarget[c] += credit(d, config);
            if (d > strayDist) {
                stray[i] = true;
                strayCount[c]++;
                strayTotal++;
            }
        }

        // 2. Coverage per stroke.
        PointIndex skelIndex = new PointIndex(skel, tau);
        boolean[][][] covered = new boolean[comps.size()][][];
        List<List<ComponentScore.StrokeScore>> strokeScores = new ArrayList<>();
        double[] coverage = new double[comps.size()];
        for (int c = 0; c < comps.size(); c++) {
            List<ReferenceShape.Stroke> strokes = comps.get(c).strokes();
            covered[c] = new boolean[strokes.size()][];
            List<ComponentScore.StrokeScore> scores = new ArrayList<>();
            double min = 1;
            for (int s = 0; s < strokes.size(); s++) {
                List<Point> samples = strokes.get(s).samples();
                covered[c][s] = new boolean[samples.size()];
                double hit = 0;
                for (int k = 0; k < samples.size(); k++) {
                    int n = skelIndex.nearest(samples.get(k), config.creditZeroDistance());
                    double cr = n < 0 ? 0 : credit(PointIndex.distance(samples.get(k), skel.get(n)), config);
                    covered[c][s][k] = cr >= 0.5;
                    hit += cr;
                }
                double cov = hit / samples.size();
                scores.add(new ComponentScore.StrokeScore(strokes.get(s).index(), strokes.get(s).kvgType(), cov,
                        Deformation.NONE, cov));
                min = Math.min(min, cov);
            }
            coverage[c] = min;
            strokeScores.add(List.copyOf(scores));
        }

        // 5. Extra marks from stray ink pixels.
        List<Point> inkPts = transform.apply(ink.inkPoints());
        boolean[][] strayMask = new boolean[ink.height()][ink.width()];
        for (int i = 0; i < inkPts.size(); i++) {
            if (!refIndex.anyWithin(inkPts.get(i), strayDist) && !extraIndex.anyWithin(inkPts.get(i), strayDist)) {
                int[] px = ink.inkPixels().get(i);
                strayMask[px[1]][px[0]] = true;
            }
        }
        double pixelArea = Math.pow(ink.unitsPerPixel() * transform.scale(), 2);
        List<StrayMark> marks = new ArrayList<>();
        List<Point> strayInk = new ArrayList<>();
        int[] extraMarks = new int[comps.size()];
        for (List<int[]> patch : patches(strayMask)) {
            double area = patch.size() * pixelArea;
            if (area < config.strayMinArea()) {
                continue;
            }
            double sx = 0, sy = 0, bestD = Double.MAX_VALUE;
            int bestComp = 0;
            for (int[] px : patch) {
                Point p = transform.apply(new Point((px[0] + 0.5) * ink.unitsPerPixel(), (px[1] + 0.5) * ink.unitsPerPixel()));
                strayInk.add(p);
                sx += p.x();
                sy += p.y();
                int n = refIndex.nearest(p, FAR);
                double d = PointIndex.distance(p, refPts.get(n));
                if (d < bestD) {
                    bestD = d;
                    bestComp = owner.get(n);
                }
            }
            extraMarks[bestComp]++;
            marks.add(new StrayMark(new Point(sx / patch.size(), sy / patch.size()), area, comps.get(bestComp).label()));
        }

        // 3-4. Precision, F1 and penalties.
        List<ComponentScore> result = new ArrayList<>();
        for (int c = 0; c < comps.size(); c++) {
            double precision = assignedCount[c] == 0 ? 1 : onTarget[c] / assignedCount[c];
            double f1 = coverage[c] + precision == 0 ? 0 : 2 * coverage[c] * precision / (coverage[c] + precision);
            double score = Math.max(0, f1 - extraMarks[c] * config.strayPenalty());
            result.add(new ComponentScore(comps.get(c).label(), comps.get(c).position(), coverage[c], precision, f1,
                    score, strokeScores.get(c), assignedCount[c], strayCount[c], extraMarks[c], Deformation.NONE, List.of()));
        }
        return new ScoreReport(List.copyOf(result), List.copyOf(marks), strayTotal, shape, skel, assigned, stray, covered,
                List.copyOf(strayInk), shape);
    }

    /**
     * Credit for a point at distance {@code d} from the other set: 1 within τ, falling linearly
     * to 0 at {@code creditZeroDistance}. With {@code creditFalloff = 1} this is the hard cutoff
     * "within τ or not".
     *
     * @param d distance in reference units
     * @param config supplies τ and the zero-credit distance
     * @return the credit, 0–1
     */
    public static double credit(double d, CheckerConfig config) {
        double full = config.tolerance(), zero = config.creditZeroDistance();
        if (d <= full) {
            return 1;
        }
        return d >= zero ? 0 : (zero - d) / (zero - full);
    }

    /** 8-connected patches of true pixels. */
    private static List<List<int[]>> patches(boolean[][] mask) {
        int h = mask.length, w = h == 0 ? 0 : mask[0].length;
        boolean[][] seen = new boolean[h][w];
        List<List<int[]>> out = new ArrayList<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!mask[y][x] || seen[y][x]) {
                    continue;
                }
                List<int[]> patch = new ArrayList<>();
                seen[y][x] = true;
                queue.add(new int[] {x, y});
                while (!queue.isEmpty()) {
                    int[] p = queue.poll();
                    patch.add(p);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = p[0] + dx, ny = p[1] + dy;
                            if (nx >= 0 && ny >= 0 && nx < w && ny < h && mask[ny][nx] && !seen[ny][nx]) {
                                seen[ny][nx] = true;
                                queue.add(new int[] {nx, ny});
                            }
                        }
                    }
                }
                out.add(patch);
            }
        }
        return out;
    }
}
