package kanji.handwriting.check.checker;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import kanji.handwriting.check.checker.align.Aligner;
import kanji.handwriting.check.checker.align.Alignment;
import kanji.handwriting.check.checker.align.AxisTransform;
import kanji.handwriting.check.checker.align.BoundedIcp;
import kanji.handwriting.check.checker.align.SimilarityTransform;
import kanji.handwriting.check.checker.geometry.PointIndex;
import kanji.handwriting.check.checker.image.InkImage;
import kanji.handwriting.check.checker.image.InkPreprocessor;
import kanji.handwriting.check.checker.reference.ConfusableRadicals;
import kanji.handwriting.check.checker.reference.ReferenceShape;
import kanji.handwriting.check.checker.reference.ReferenceShapeLoader;
import kanji.handwriting.check.checker.render.OverlayRenderer;
import kanji.handwriting.check.checker.score.ComponentScore;
import kanji.handwriting.check.checker.score.Deformation;
import kanji.handwriting.check.checker.score.PartScore;
import kanji.handwriting.check.checker.score.ExtraInk;
import kanji.handwriting.check.checker.score.RunOn;
import kanji.handwriting.check.checker.score.ScoreReport;
import kanji.handwriting.check.checker.score.Scorer;
import kanji.handwriting.check.checker.score.SnapScorer;
import kanji.handwriting.check.model.Point;
import kanji.handwriting.check.service.ReferenceRepository;

/**
 * Checks a handwritten kanji image against KanjiVG by shape alone (no stroke order or
 * direction): load reference → preprocess image → align → score → render.
 */
public final class KanjiChecker {
    private final ReferenceShapeLoader loader;
    private final ConfusableRadicals confusables;

    /**
     * Creates a checker.
     *
     * @param repository source of KanjiVG data
     */
    public KanjiChecker(ReferenceRepository repository) {
        this.loader = new ReferenceShapeLoader(repository);
        this.confusables = new ConfusableRadicals(loader);
    }

    /**
     * Checks a drawing stored as an image file.
     *
     * @param image PNG (or other ImageIO format) of the drawing on its square canvas
     * @param kanji the target character
     * @param config settings
     * @return the result
     * @throws IOException if the image cannot be read
     * @throws IllegalArgumentException if there is no KanjiVG data for {@code kanji}, or it is kana
     */
    public CheckResult check(Path image, String kanji, CheckerConfig config) throws IOException {
        BufferedImage img = ImageIO.read(image.toFile());
        if (img == null) {
            throw new IOException("Not a readable image: " + image);
        }
        return check(img, kanji, config);
    }

    /**
     * Checks a drawing.
     *
     * @param image the drawing on its square canvas
     * @param kanji the target character
     * @param config settings
     * @return the result
     * @throws IllegalArgumentException if there is no KanjiVG data for {@code kanji}, or it is kana
     *     (see {@link #isKana})
     */
    public CheckResult check(BufferedImage image, String kanji, CheckerConfig config) {
        if (isKana(kanji)) {
            throw new IllegalArgumentException("Kana are not checked yet (kanji only): " + kanji);
        }
        List<ReferenceShape> forms = loader.loadAll(kanji, config);
        if (forms.isEmpty()) {
            throw new IllegalArgumentException("No KanjiVG data for " + kanji);
        }
        InkImage ink = InkPreprocessor.process(image, config);
        // A character may have several acceptable forms (e.g. お with a straight first stroke and
        // dot). The drawing is checked against each, and the best result is kept.
        CheckResult best = null;
        ReferenceShape bestShape = null;
        for (ReferenceShape shape : forms) {
            CheckResult r = check(shape, ink, kanji, config, null, null);
            if (best == null || r.verdict().ordinal() < best.verdict().ordinal()
                    || r.verdict() == best.verdict() && r.score() > best.score()) {
                best = r;
                bestShape = shape;
            }
        }
        // A wrong radical is a whole look-alike shape (彳 for 亻, 目 for 日), not a random line:
        // if the kanji with a radical swapped for a look-alike fits clearly better, that radical
        // was written wrong.
        ConfusableRadicals.Swap worst = null;
        double gain = 0;
        for (ConfusableRadicals.Swap swap : confusables.swaps(bestShape, config)) {
            // Only where the radical as drawn shows a sign of being another one: ink it does not
            // account for (an extra stroke: 彳 for 亻), or a stroke that is missing (冫 for 氵). A
            // merely sloppy stroke is no reason to suspect a different radical.
            String expected = bestShape.components().get(swap.component()).label();
            boolean suspicious = best.components().stream().filter(cs -> cs.label().equals(expected))
                    .anyMatch(cs -> cs.precision() < CONFUSABLE_PRECISION || cs.extraMarks() > 0
                            || cs.strokes().stream().anyMatch(st -> st.coverage() < config.missingCoverage()));
            if (!suspicious) {
                continue;
            }
            CheckResult r = check(swap.model(), ink, kanji, config, null, null);
            double g = r.score() - best.score();
            // The look-alike must account for all the ink where it stands: one with fewer strokes
            // (冫 for 氵) must not win just by leaving out a stroke that was drawn.
            boolean explainsInk = r.components().stream().filter(cs -> swap.radical().equals(cs.label()))
                    .allMatch(cs -> cs.precision() >= CONFUSABLE_PRECISION && cs.extraMarks() == 0);
            if (explainsInk && r.verdict().ordinal() <= best.verdict().ordinal() && g > gain) {
                gain = g;
                worst = swap;
            }
        }
        if (worst != null && gain >= CONFUSABLE_UNSURE) {
            Verdict cap = gain >= CONFUSABLE_FAIL ? Verdict.FAIL : Verdict.UNSURE;
            String label = ConfusableRadicals.baseLabel(bestShape.components().get(worst.component()).label());
            best = check(bestShape, ink, kanji, config, cap, Messages.of(config.language()).looksLike(label, worst.radical()));
        }
        return best;
    }

    /** A look-alike radical fitting this much better makes the drawing UNSURE... */
    static final double CONFUSABLE_UNSURE = 0.1;
    /** ...and this much better, FAIL. */
    static final double CONFUSABLE_FAIL = 0.25;
    /**
     * The share of its ink a radical must account for (below it, it may be a look-alike with
     * more strokes); a look-alike must account for at least as much.
     */
    static final double CONFUSABLE_PRECISION = 0.95;

    /**
     * Checks a drawing against one model.
     *
     * @param cap if not null, the verdict is at best this
     * @param note if not null, a feedback line added first
     */
    private static CheckResult check(ReferenceShape shape, InkImage ink, String kanji, CheckerConfig config, Verdict cap,
                                     String note) {

        List<Point> refPts = points(shape);
        Alignment alignment = Aligner.align(ink.skeletonPoints(), refPts, config);
        ReferenceShape reshaped = matchProportions(shape, refPts, ink, alignment, config);
        if (reshaped != shape) {
            shape = reshaped;
            alignment = Aligner.align(ink.skeletonPoints(), points(shape), config);
        }

        ScoreReport report = config.scoring() == CheckerConfig.Scoring.SNAP
                ? SnapScorer.score(shape, ink, alignment.transform(), config)
                : Scorer.score(shape, ink, alignment.transform(), config);
        double min = report.components().stream().mapToDouble(ComponentScore::score).min().orElse(0);
        // Position in the grid is never penalised. A tilt beyond the limit is; strict grading also
        // takes a little off for the overall size and a slight tilt (see framingPenalty).
        boolean penalised = alignment.rotationClamped();
        double score = Math.max(0, min - (penalised ? config.clampPenalty() : 0) - framingPenalty(alignment, config));
        Verdict verdict = verdict(report, score, config);
        // A stroke that stops at a stroke it should cross and carry on past (天 for 夫). Like
        // stroke length, only judged when length is (not RELAXED).
        List<Integer> unprotruding = config.unsureRelativeLength() < RELATIVE_OFF
                ? RunOn.missingProtrusions(shape, report.scoredShape(), report.alignedSkeleton()) : List.of();
        if (verdict == Verdict.PASS && !unprotruding.isEmpty()) {
            verdict = Verdict.UNSURE;
        }
        // Ink no stroke accounts for, though it lies close to the strokes: an extra short line
        // (体's bar on 休's 木, a second sweep making 亻 into 彳, 日 drawn inside 回).
        boolean extraLine = config.scoring() == CheckerConfig.Scoring.SNAP
                && ExtraInk.largestPiece(report, config) >= EXTRA_LINE_LENGTH;
        if (verdict == Verdict.PASS && extraLine) {
            verdict = Verdict.UNSURE;
        }

        if (cap != null && cap.ordinal() > verdict.ordinal()) {
            verdict = cap;
        }
        List<ComponentScore> sorted = new ArrayList<>(report.components());
        sorted.sort(Comparator.comparingDouble(ComponentScore::score));
        List<String> alignmentFeedback = alignmentFeedback(alignment, ink.skeletonPoints().isEmpty(), config);
        List<String> feedback = new ArrayList<>();
        if (note != null) {
            feedback.add(note);
        }
        feedback.addAll(alignmentFeedback);
        feedback.addAll(componentFeedback(sorted, config));
        Messages messages = Messages.of(config.language());
        RunOn.overshoots(report.scoredShape(), report.alignedSkeleton())
                .forEach(i -> feedback.add(messages.overshoot(i + 1)));
        unprotruding.forEach(i -> feedback.add(messages.noProtrusion(i + 1)));
        if (extraLine) {
            feedback.add(messages.extraLine());
        }

        BufferedImage overlay = OverlayRenderer.render(shape, report, verdict, score, alignmentFeedback,
                Messages.of(config.language()));
        return new CheckResult(kanji, shape.sourceKanji(), shape.variant(), verdict, score, List.copyOf(sorted), report.strayMarks(),
                report.strayPoints(), alignment, List.copyOf(feedback), overlay);
    }

    /**
     * Whether a character is hiragana or katakana. Kana are not checked for now: their handwritten
     * forms vary too much, with no fixed rules to check against.
     *
     * @param ch a single character
     * @return true for Hiragana, Katakana, Katakana Phonetic Extensions and halfwidth katakana
     */
    public static boolean isKana(String ch) {
        if (ch == null || ch.isEmpty()) {
            return false;
        }
        Character.UnicodeBlock b = Character.UnicodeBlock.of(ch.codePointAt(0));
        return b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || ch.codePointAt(0) >= 0xFF66 && ch.codePointAt(0) <= 0xFF9F;
    }

    /**
     * The three-way verdict:
     * <ul>
     *   <li>{@link Verdict#FAIL} for a definite error: a stroke less than {@code missingCoverage}
     *       covered (missing), a stroke drawn in the wrong direction (turned more than 45°, as
     *       with a dot written ／ for ＼), an extra mark, or a score below
     *       {@code unsureThreshold};
     *   <li>{@link Verdict#PASS} when the score reaches {@code passThreshold}, every stroke is at
     *       least {@code minStrokeCoverage} covered, and every long stroke's drawn length is
     *       plausible (see {@link #lengthsPlausible});
     *   <li>{@link Verdict#UNSURE} otherwise, also when a stroke meant to stop at another stroke is
     *       drawn on through it (see {@link RunOn#overshoots}), or a long stroke is drawn noticeably too
     *       long or too short: plausible but messy
     *       handwriting, or a subtle length or proportion error, for a person to check.
     * </ul>
     */
    static Verdict verdict(ScoreReport report, double score, CheckerConfig config) {
        List<ComponentScore.StrokeScore> strokes = report.components().stream().flatMap(c -> c.strokes().stream()).toList();
        boolean missing = strokes.stream().anyMatch(s -> s.coverage() < config.missingCoverage());
        boolean wrongWay = strokes.stream().anyMatch(s -> Math.abs(s.deformation().rotationDegrees()) > WRONG_DIRECTION);
        if (missing || wrongWay || !report.strayMarks().isEmpty() || score < config.unsureThreshold()) {
            return Verdict.FAIL;
        }
        boolean covered = strokes.stream().allMatch(s -> s.coverage() >= config.minStrokeCoverage());
        return score >= config.passThreshold() && covered && lengthsPlausible(report, config)
                && relativeLengthsPlausible(report, config) && anglesPlausible(report, config)
                && RunOn.overshoots(report.scoredShape(), report.alignedSkeleton()).isEmpty()
                ? Verdict.PASS : Verdict.UNSURE;
    }

    /**
     * Whether every stroke's drawn length is plausible: not shorter than allowed by
     * {@code unsureLengthChange} nor longer than allowed by {@code unsureLengthGrowth}, unless the
     * difference is at most {@code unsureLengthDifference} units (a short stroke's measured length
     * is noisy, while 日's short middle line drawn twice as long is still caught).
     * The drawn length is the snapped stroke's length plus however far the ink continues straight
     * on past each snapped end: snapping alone under-measures a stroke that runs long (末's long
     * top stroke drawn for 未's short one), and a neighbour's ink can make a snap shrink.
     */
    static boolean lengthsPlausible(ScoreReport report, CheckerConfig config) {
        java.util.Map<Integer, String> types = new java.util.HashMap<>();
        report.scoredShape().components().forEach(c -> c.strokes().forEach(s -> types.put(s.index(), s.kvgType())));
        return strokeLengths(report, config).entrySet().stream().allMatch(e -> {
            boolean sweep = isSweep(types.get(e.getKey()));
            double shortest = 1 / (1 + (sweep ? config.sweepLengthChange() : config.unsureLengthChange()));
            double longest = 1 + (sweep ? config.sweepLengthGrowth() : config.unsureLengthGrowth());
            double[] l = e.getValue();
            double r = l[0] / l[1];
            return r >= shortest && r <= longest || Math.abs(l[0] - l[1]) <= config.unsureLengthDifference();
        });
    }

    /**
     * Whether a KanjiVG stroke type is a left-falling sweep (㇒). Handwriting varies these most
     * in length: 亻's and 禾's first strokes drawn short, 手's first stroke long.
     */
    public static boolean isSweep(String kvgType) {
        return kvgType != null && kvgType.startsWith("㇒");
    }

    /**
     * Whether parallel strokes side by side keep which one is longer. For two horizontal strokes
     * one above the other (or two vertical ones side by side), each at least
     * {@link #RELATIVE_MIN_LENGTH} long, the drawing is implausible if
     * <ul>
     *   <li>the model makes one clearly longer (by at least {@code unsureRelativeLength}) and the
     *       drawing keeps less than {@link #RELATIVE_KEPT} of that difference (土 drawn with two
     *       equal lines), or
     *   <li>the model makes one noticeably longer (by {@link #RELATIVE_NOTICEABLE}) and the drawing
     *       makes the other one noticeably longer instead (末 for 未, 士 for 土).
     * </ul>
     * Exaggerating the model's difference is fine: handwriting often does. Measured on
     * handwriting, the ratio alone differs from KanjiVG's by 30–80% in correct drawings, as much
     * as in 土/士 swaps; which line is the longer one is what tells them apart.
     */
    static boolean relativeLengthsPlausible(ScoreReport report, CheckerConfig config) {
        if (config.unsureRelativeLength() >= RELATIVE_OFF) {
            return true;
        }
        double clear = Math.log(1 + config.unsureRelativeLength());
        java.util.SortedMap<Integer, double[]> lengths = strokeLengths(report, config);
        List<ReferenceShape.Stroke> strokes = new ArrayList<>();
        java.util.Map<Integer, Integer> componentOf = new java.util.HashMap<>();
        List<ReferenceShape.Component> comps = report.scoredShape().components();
        for (int c = 0; c < comps.size(); c++) {
            for (ReferenceShape.Stroke st : comps.get(c).strokes()) {
                strokes.add(st);
                componentOf.put(st.index(), c);
            }
        }
        for (int i = 0; i < strokes.size(); i++) {
            for (int j = i + 1; j < strokes.size(); j++) {
                ReferenceShape.Stroke a = strokes.get(i), b = strokes.get(j);
                int axis = parallelAxis(a, b);
                if (axis < 0) {
                    continue;
                }
                double[] la = lengths.get(a.index()), lb = lengths.get(b.index());
                if (la == null || lb == null || la[1] < RELATIVE_MIN_LENGTH || lb[1] < RELATIVE_MIN_LENGTH) {
                    continue;
                }
                // A stroke that is a component on its own (未's and 末's top line): the model ratio
                // from KanjiVG itself (before snapping), so that the component, scaled up to fit a
                // line drawn too long, does not carry the model along with it. Otherwise the ratio
                // is taken as components and parts were placed: they may legitimately change
                // proportions in handwriting.
                double ma = modelLength(report, a.index()), mb = modelLength(report, b.index());
                boolean loneStroke = comps.get(componentOf.get(a.index())).strokes().size() == 1
                        || comps.get(componentOf.get(b.index())).strokes().size() == 1;
                double model = Math.log(loneStroke && ma > 0 && mb > 0 ? ma / mb : la[1] / lb[1]);
                double drawn = Math.log(la[0] / lb[0]);
                boolean reversed = model * drawn < 0 && Math.abs(model) >= RELATIVE_NOTICEABLE
                        && Math.abs(drawn) >= RELATIVE_NOTICEABLE;
                boolean lost = Math.abs(model) >= clear
                        && (model * drawn <= 0 || Math.abs(drawn) < RELATIVE_KEPT * Math.abs(model));
                if (reversed || lost) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Length of stroke {@code index} in the unsnapped model, or 0 if unknown. */
    private static double modelLength(ScoreReport report, int index) {
        if (report.modelShape() == null) {
            return 0;
        }
        for (ReferenceShape.Component c : report.modelShape().components()) {
            for (ReferenceShape.Stroke st : c.strokes()) {
                if (st.index() == index) {
                    return length(st.path());
                }
            }
        }
        return 0;
    }

    /** Model length, in reference units, from which strokes take part in the relative-length check. */
    static final double RELATIVE_MIN_LENGTH = 25;
    /** Fraction of a clear length difference (log ratio) a drawing must keep. */
    static final double RELATIVE_KEPT = 1.0 / 3;
    /** A length difference (log ratio) this large counts when the order is reversed: about 11%. */
    static final double RELATIVE_NOTICEABLE = 0.1;
    /** An {@code unsureRelativeLength} this large turns the relative-length check off. */
    static final double RELATIVE_OFF = 10;

    /**
     * 0 if both strokes are horizontal (㇐) and overlap left to right by at least half the shorter,
     * 1 if both are vertical (㇑) and overlap top to bottom likewise, otherwise -1.
     */
    private static int parallelAxis(ReferenceShape.Stroke a, ReferenceShape.Stroke b) {
        String ta = a.kvgType(), tb = b.kvgType();
        if (ta == null || tb == null || ta.isEmpty() || tb.isEmpty() || ta.charAt(0) != tb.charAt(0)) {
            return -1;
        }
        int axis = ta.charAt(0) == '㇐' ? 0 : ta.charAt(0) == '㇑' ? 1 : -1;
        if (axis < 0) {
            return -1;
        }
        double[] ra = range(a.path(), axis), rb = range(b.path(), axis);
        double overlap = Math.min(ra[1], rb[1]) - Math.max(ra[0], rb[0]);
        return overlap >= 0.5 * Math.min(ra[1] - ra[0], rb[1] - rb[0]) ? axis : -1;
    }

    private static double[] range(List<Point> path, int axis) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Point p : path) {
            double v = axis == 0 ? p.x() : p.y();
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new double[] {lo, hi};
    }

    /**
     * Drawn length over model length for each stroke.
     *
     * @return ratios keyed by 0-based stroke index, in stroke order
     */
    static java.util.SortedMap<Integer, Double> lengthRatios(ScoreReport report, CheckerConfig config) {
        java.util.SortedMap<Integer, Double> ratios = new java.util.TreeMap<>();
        strokeLengths(report, config).forEach((k, l) -> ratios.put(k, l[0] / l[1]));
        return ratios;
    }

    /**
     * Drawn and model length of each stroke, in reference units.
     *
     * @return {drawn, model} keyed by 0-based stroke index, in stroke order
     */
    static java.util.SortedMap<Integer, double[]> strokeLengths(ScoreReport report, CheckerConfig config) {
        java.util.SortedMap<Integer, double[]> lengths = new java.util.TreeMap<>();
        List<ReferenceShape.Stroke> paths = new ArrayList<>();
        List<ComponentScore.StrokeScore> scores = new ArrayList<>();
        List<ReferenceShape.Component> snapped = report.scoredShape().components();
        List<boolean[]> covered = new ArrayList<>();
        for (int c = 0; c < snapped.size(); c++) {
            paths.addAll(snapped.get(c).strokes());
            scores.addAll(report.components().get(c).strokes());
            for (int s = 0; s < snapped.get(c).strokes().size(); s++) {
                boolean[][][] all = report.strokeCovered();
                covered.add(all != null && c < all.length && all[c] != null && s < all[c].length ? all[c][s] : null);
            }
        }
        // For each skeleton point, which snapped strokes it lies near: ink at a junction or
        // crossing belongs to the other stroke, not to a run-on.
        List<Point> skel = report.alignedSkeleton();
        double near = config.tolerance() / 2;
        boolean[][] nearStroke = new boolean[paths.size()][skel.size()];
        for (int j = 0; j < paths.size(); j++) {
            PointIndex index = new PointIndex(paths.get(j).samples(), near);
            for (int i = 0; i < skel.size(); i++) {
                nearStroke[j][i] = index.anyWithin(skel.get(i), near);
            }
        }
        for (int s = 0; s < paths.size(); s++) {
            List<Point> path = paths.get(s).path();
            // Only the inked part of the snapped stroke counts as drawn: a stroke cut short can
            // still snap at full length over the gap.
            boolean[] cov = covered.get(s);
            double inkedFraction = 1;
            if (cov != null && cov.length > 0) {
                int n = 0;
                for (boolean b : cov) {
                    n += b ? 1 : 0;
                }
                inkedFraction = n / (double) cov.length;
            }
            double snappedLength = length(path) * inkedFraction;
            // The model stroke as its component and parts were placed: handwriting resizes whole
            // radicals freely (a side radical drawn small), and that is judged as placement.
            double modelLength = length(path) / scores.get(s).deformation().scale();
            List<Point> own = new ArrayList<>();
            for (int i = 0; i < skel.size(); i++) {
                boolean other = false;
                for (int j = 0; j < paths.size() && !other; j++) {
                    other = j != s && nearStroke[j][i];
                }
                if (!other) {
                    own.add(skel.get(i));
                }
            }
            PointIndex ownIndex = new PointIndex(own, 4);
            double drawn = snappedLength + runOn(path, false, ownIndex) + runOn(path, true, ownIndex);
            lengths.put(paths.get(s).index(), new double[] {drawn, modelLength});
        }
        return lengths;
    }

    /**
     * Whether no long stroke is turned more than {@code unsureRotationDegrees} from the model
     * (strokes shorter than {@code unsureRotationMinLength} are not checked).
     */
    static boolean anglesPlausible(ScoreReport report, CheckerConfig config) {
        List<ReferenceShape.Component> snapped = report.scoredShape().components();
        for (int c = 0; c < snapped.size(); c++) {
            List<ReferenceShape.Stroke> paths = snapped.get(c).strokes();
            List<ComponentScore.StrokeScore> scores = report.components().get(c).strokes();
            for (int s = 0; s < paths.size(); s++) {
                Deformation d = scores.get(s).deformation();
                double modelLength = length(paths.get(s).path()) / d.scale();
                if (modelLength >= config.unsureRotationMinLength() && Math.abs(d.rotationDegrees()) > config.unsureRotationDegrees()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** How far skeleton ink continues straight on past one end of a path, allowing small gaps. */
    private static double runOn(List<Point> path, boolean atStart, PointIndex skeleton) {
        int n = path.size();
        Point end = atStart ? path.get(0) : path.get(n - 1);
        // Direction of travel at the end, from a point about 4 units back along the path.
        Point back = end;
        double walked = 0;
        for (int i = 1; i < n && walked < 4; i++) {
            Point p = atStart ? path.get(i) : path.get(n - 1 - i);
            walked += PointIndex.distance(p, back);
            back = p;
        }
        double len = PointIndex.distance(end, back);
        if (len < 1e-6) {
            return 0;
        }
        double ux = (end.x() - back.x()) / len, uy = (end.y() - back.y()) / len;
        double last = 0;
        for (double t = 1; t - last <= 3 && t < 109; t += 1) {
            if (skeleton.anyWithin(new Point(end.x() + t * ux, end.y() + t * uy), 2)) {
                last = t;
            }
        }
        return last;
    }

    private static double length(List<Point> path) {
        double sum = 0;
        for (int i = 1; i < path.size(); i++) {
            sum += PointIndex.distance(path.get(i - 1), path.get(i));
        }
        return sum;
    }

    private static List<Point> points(ReferenceShape shape) {
        List<Point> pts = new ArrayList<>();
        shape.components().forEach(c -> c.strokes().forEach(s -> pts.addAll(s.samples())));
        return pts;
    }

    /**
     * The reference reshaped to the drawing's proportions. Handwriting, especially in small grid
     * cells, is often wider and shorter (or narrower and taller) than KanjiVG's model; a similarity
     * alignment cannot correct that, and strokes then snap to the wrong lines. The model is fitted
     * to the aligned ink with separate horizontal and vertical scales, and its height relative to
     * its width is changed to match, within {@code maxAspectChange} (its area is kept).
     *
     * @return the reshaped reference, or {@code shape} itself if the proportions already match
     */
    static ReferenceShape matchProportions(ReferenceShape shape, List<Point> refPts, InkImage ink, Alignment alignment,
                                           CheckerConfig config) {
        if (config.maxAspectChange() <= 0 || ink.skeletonPoints().size() < 3) {
            return shape;
        }
        double tau = config.tolerance(), pair = config.icpMaxPairDistance(), c = InkPreprocessor.REFERENCE_SIZE / 2;
        PointIndex aligned = new PointIndex(alignment.transform().apply(ink.skeletonPoints()), pair);
        BoundedIcp.AxisResult fit = BoundedIcp.searchAxes(refPts, aligned, c, c, config.maxTranslation() / 2,
                config.maxAspectChange(), pair, pair / 2, tau / 2, config.snap().iterations(), aligned, tau / 2);
        double limit = Math.log(1 + config.maxAspectChange());
        double logAspect = Math.max(-limit, Math.min(limit, Math.log(fit.transform().sy() / fit.transform().sx())));
        if (Math.abs(logAspect) < Math.log(1.03)) {
            return shape;
        }
        double k = Math.exp(logAspect / 2);
        return shape.map(new AxisTransform(1 / k, k, 0, 0, c, c));
    }

    /**
     * Score lost for the whole drawing's size and tilt: {@code sizePenalty} per 10 percentage
     * points the size is beyond {@code sizeNoteTolerance} from the grid, plus {@code tiltPenalty}
     * per degree of tilt beyond {@code tiltFreeDegrees}, at most
     * {@link CheckerConfig#MAX_FRAMING_PENALTY}. Both are 0 except in strict grading.
     *
     * @param a the alignment (its unclamped fit gives the drawing's size and tilt)
     * @param config the penalties
     * @return the penalty, 0 to {@code MAX_FRAMING_PENALTY}
     */
    /**
     * A piece of ink at least this long (reference units) that no stroke accounts for is an extra
     * line. Measured on handwriting: correct drawings leave at most 9 units, extra strokes 12–21.
     */
    static final double EXTRA_LINE_LENGTH = 10;

    /** A drawing up to this much larger than the model costs no score under strict grading. */
    static final double LARGE_SIZE_FREE = 0.3;

    static double framingPenalty(Alignment a, CheckerConfig config) {
        SimilarityTransform w = a.wanted();
        double size = 1 / w.scale(); // drawing size relative to the grid
        // Writing large fills a grid cell naturally (about 125% of KanjiVG's box), so only a
        // drawing clearly larger than that loses score; a small one loses it sooner.
        double sizeExcess = size > 1 ? Math.max(0, size - 1 - LARGE_SIZE_FREE)
                : Math.max(0, 1 - size - config.sizeNoteTolerance());
        double tiltExcess = Math.max(0, Math.abs(w.rotationDegrees()) - config.tiltFreeDegrees());
        return Math.min(CheckerConfig.MAX_FRAMING_PENALTY,
                config.sizePenalty() * sizeExcess / 0.1 + config.tiltPenalty() * tiltExcess);
    }

    static List<String> alignmentFeedback(Alignment a, boolean empty, CheckerConfig config) {
        Messages m = Messages.of(config.language());
        List<String> out = new ArrayList<>();
        if (empty) {
            out.add(m.noInk());
            return out;
        }
        SimilarityTransform w = a.wanted();
        if (Math.abs(w.scale() - 1) > config.sizeNoteTolerance()) {
            // Informational unless strict grading scores the size.
            out.add(m.sizeNote(w.scale() > 1, 100 / w.scale(), config.sizePenalty() > 0));
        }
        boolean tiltScored = config.tiltPenalty() > 0 && Math.abs(w.rotationDegrees()) > config.tiltFreeDegrees();
        if (a.rotationClamped() || tiltScored) {
            double tilt = -w.rotationDegrees(); // the drawing is rotated opposite to the correction
            out.add(m.tilted(Math.abs(tilt), tilt > 0));
        }
        if (Math.max(Math.abs(w.tx()), Math.abs(w.ty())) > config.offCentreNote()) {
            // Informational: position in the grid does not affect the score.
            out.add(m.offCentre(w.tx(), w.ty(), config.offCentreNote()));
        }
        return out;
    }

    /** Parts whose placement score is below this are mentioned in feedback. */
    private static final double PART_MENTION = 0.9;
    /** A stroke turned by more than this many degrees is described as drawn in the wrong direction. */
    private static final double WRONG_DIRECTION = 45;

    static List<String> componentFeedback(List<ComponentScore> worstFirst, CheckerConfig config) {
        Messages m = Messages.of(config.language());
        SnapConfig sc = config.snap();
        List<String> out = new ArrayList<>();
        for (ComponentScore c : worstFirst) {
            boolean strokeMissing = c.strokes().stream().anyMatch(s -> s.coverage() < config.minStrokeCoverage());
            if (c.score() >= config.passThreshold() && !strokeMissing && c.extraMarks() == 0) {
                continue;
            }
            List<String> problems = new ArrayList<>();
            // The whole component out of place (snap scoring only).
            describe(c.placement(), 0, sc.componentShift(), sc.componentRotation(), sc.componentSize(), m, problems);
            // Parts inside it noticeably out of place or proportion.
            for (PartScore part : c.parts()) {
                if (part.placement().score() < PART_MENTION) {
                    List<String> about = new ArrayList<>();
                    describe(part.placement(), 0, sc.partShift(), sc.partRotation(), sc.partSize(), m, about);
                    about.forEach(t -> problems.add(m.part(m.label(part.label()), t)));
                }
            }
            for (ComponentScore.StrokeScore s : c.strokes()) {
                int n = s.strokeIndex() + 1;
                if (s.coverage() < config.minStrokeCoverage()) {
                    problems.add(m.strokeMissing(n, 100 * s.coverage()));
                } else if (Math.abs(s.deformation().rotationDegrees()) > WRONG_DIRECTION) {
                    problems.add(m.wrongDirection(n, s.deformation().rotationDegrees()));
                } else if (s.score() < config.passThreshold()) {
                    describe(s.deformation(), n, sc.strokeShift(), sc.strokeRotation(), sc.strokeSize(), m, problems);
                }
            }
            if (c.precision() < 0.9) {
                problems.add(m.extraInk(100 * c.precision()));
            }
            if (c.extraMarks() > 0) {
                problems.add(m.extraMarks(c.extraMarks()));
            }
            if (problems.isEmpty()) {
                problems.add(m.shapeDiffers());
            }
            out.add(m.componentLine(m.label(c.label()), problems));
        }
        return out;
    }

    /** Adds a message for each part of a movement that goes beyond its free allowance. */
    private static void describe(Deformation d, int stroke, Deformation.Allowance shift, Deformation.Allowance rotation,
                                 Deformation.Allowance size, Messages m, List<String> problems) {
        if (d.shift() > shift.free()) {
            problems.add(m.shifted(stroke, d.dx(), d.dy()));
        }
        if (Math.abs(Math.log(d.scale())) > Math.log(1 + size.free())) {
            problems.add(m.resized(stroke, d.scale()));
        }
        if (Math.abs(d.rotationDegrees()) > rotation.free()) {
            problems.add(m.rotated(stroke, d.rotationDegrees()));
        }
    }
}
