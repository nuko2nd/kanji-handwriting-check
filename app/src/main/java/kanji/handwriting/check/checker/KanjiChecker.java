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
import kanji.handwriting.check.checker.align.SimilarityTransform;
import kanji.handwriting.check.checker.geometry.PointIndex;
import kanji.handwriting.check.checker.image.InkImage;
import kanji.handwriting.check.checker.image.InkPreprocessor;
import kanji.handwriting.check.checker.reference.ReferenceShape;
import kanji.handwriting.check.checker.reference.ReferenceShapeLoader;
import kanji.handwriting.check.checker.render.OverlayRenderer;
import kanji.handwriting.check.checker.score.ComponentScore;
import kanji.handwriting.check.checker.score.Deformation;
import kanji.handwriting.check.checker.score.PartScore;
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

    /**
     * Creates a checker.
     *
     * @param repository source of KanjiVG data
     */
    public KanjiChecker(ReferenceRepository repository) {
        this.loader = new ReferenceShapeLoader(repository);
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
        for (ReferenceShape shape : forms) {
            CheckResult r = check(shape, ink, kanji, config);
            if (best == null || r.verdict().ordinal() < best.verdict().ordinal()
                    || r.verdict() == best.verdict() && r.score() > best.score()) {
                best = r;
            }
        }
        return best;
    }

    private static CheckResult check(ReferenceShape shape, InkImage ink, String kanji, CheckerConfig config) {

        List<Point> refPts = new ArrayList<>();
        shape.components().forEach(c -> c.strokes().forEach(s -> refPts.addAll(s.samples())));
        Alignment alignment = Aligner.align(ink.skeletonPoints(), refPts, config);

        ScoreReport report = config.scoring() == CheckerConfig.Scoring.SNAP
                ? SnapScorer.score(shape, ink, alignment.transform(), config)
                : Scorer.score(shape, ink, alignment.transform(), config);
        double min = report.components().stream().mapToDouble(ComponentScore::score).min().orElse(0);
        // Overall size and position in the grid are never penalised; only a tilt beyond the limit is.
        boolean penalised = alignment.rotationClamped();
        double score = Math.max(0, min - (penalised ? config.clampPenalty() : 0));
        Verdict verdict = verdict(report, score, config);

        List<ComponentScore> sorted = new ArrayList<>(report.components());
        sorted.sort(Comparator.comparingDouble(ComponentScore::score));
        List<String> alignmentFeedback = alignmentFeedback(alignment, ink.skeletonPoints().isEmpty(), config);
        List<String> feedback = new ArrayList<>(alignmentFeedback);
        feedback.addAll(componentFeedback(sorted, config));

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
     *   <li>{@link Verdict#UNSURE} otherwise, including when a long stroke is drawn noticeably too
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
        return score >= config.passThreshold() && covered && lengthsPlausible(report, config) && anglesPlausible(report, config)
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
        double shortest = 1 / (1 + config.unsureLengthChange()), longest = 1 + config.unsureLengthGrowth();
        return strokeLengths(report, config).values().stream().allMatch(l -> {
            double r = l[0] / l[1];
            return r >= shortest && r <= longest || Math.abs(l[0] - l[1]) <= config.unsureLengthDifference();
        });
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
        for (int c = 0; c < snapped.size(); c++) {
            paths.addAll(snapped.get(c).strokes());
            scores.addAll(report.components().get(c).strokes());
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
            double snappedLength = length(path);
            double modelLength = snappedLength / scores.get(s).deformation().scale();
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

    static List<String> alignmentFeedback(Alignment a, boolean empty, CheckerConfig config) {
        Messages m = Messages.of(config.language());
        List<String> out = new ArrayList<>();
        if (empty) {
            out.add(m.noInk());
            return out;
        }
        SimilarityTransform w = a.wanted();
        if (Math.abs(w.scale() - 1) > config.sizeNoteTolerance()) {
            // Informational: size does not affect the score.
            out.add(m.sizeNote(w.scale() > 1, 100 / w.scale()));
        }
        if (a.rotationClamped()) {
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
            if (c.score() >= config.passThreshold() && !strokeMissing) {
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
