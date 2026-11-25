package kanji.handwriting.check.checker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kanji.handwriting.check.checker.score.ComponentScore;
import kanji.handwriting.check.checker.score.StrayMark;
import kanji.handwriting.check.model.Point;
import kanji.handwriting.check.service.ReferenceRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * End-to-end tests on synthetic drawings rendered from KanjiVG itself. No training data: a
 * correct drawing is the reference rendered with a pen, and each error is applied to it
 * programmatically. Test kanji cover enclosures (国, 道), small details (犬, 玉), length
 * confusables (未/末, 土/士) and split components (衛).
 */
class KanjiCheckerTest {
    private static final KanjiChecker CHECKER = new KanjiChecker(new ReferenceRepository());
    private static final CheckerConfig CONFIG = CheckerConfig.defaults();
    private static final String ALL = "国,道,犬,玉,未,末,土,士,衛,語";

    private static CheckResult check(String kanji, BufferedImage drawing) {
        return CHECKER.check(drawing, kanji, CONFIG);
    }

    /** The stroke with the lowest score, 1-based. */
    private static int worstStroke(CheckResult r) {
        return r.components().stream().flatMap(c -> c.strokes().stream())
                .min(Comparator.comparingDouble(ComponentScore.StrokeScore::score)).orElseThrow().strokeIndex() + 1;
    }

    private static Set<Integer> strokes(String spec) {
        return Arrays.stream(spec.split(" ")).map(Integer::parseInt).collect(Collectors.toSet());
    }

    @Nested
    class CorrectDrawings {
        @ParameterizedTest
        @ValueSource(strings = {"国", "道", "犬", "玉", "未", "末", "土", "士", "衛", "語"})
        void referenceRenderingPassesNearPerfectly(String kanji) {
            CheckResult r = check(kanji, TestDrawings.render(TestDrawings.strokes(kanji)));
            assertTrue(r.pass(), () -> kanji + " failed: " + r.feedback());
            assertTrue(r.score() >= 0.98, () -> kanji + " scored " + r.score());
            assertFalse(r.alignment().anyClamped());
            assertTrue(r.strayMarks().isEmpty());
        }

        @ParameterizedTest
        @CsvSource({"国, 2.0", "国, 6.0", "道, 2.0", "道, 6.0", "犬, 6.0", "衛, 2.0", "衛, 6.0", "語, 6.0"})
        void penWidthDoesNotMatter(String kanji, double pen) {
            CheckResult r = check(kanji, TestDrawings.render(TestDrawings.strokes(kanji), pen, List.of(), 0));
            assertTrue(r.pass(), () -> kanji + " with pen " + pen + " failed: " + r.feedback());
        }

        @ParameterizedTest
        @CsvSource({"国, 1", "国, 2", "道, 1", "犬, 3", "玉, 4", "未, 5", "末, 6", "土, 7", "士, 8", "衛, 9", "語, 10"})
        void smallJitterStillPasses(String kanji, long seed) {
            CheckResult r = check(kanji, TestDrawings.render(TestDrawings.jitter(TestDrawings.strokes(kanji), 1.5, seed)));
            assertTrue(r.pass(), () -> kanji + " with jitter failed: " + r.feedback());
        }

        /**
         * Imitates careful but imperfect handwriting: each stroke independently shifted up to 2
         * units, rotated up to 4° and resized up to 10%, then wobbled. The strict brief settings
         * (τ = 5, hard cutoff) failed some of these; the defaults must accept them all.
         */
        @ParameterizedTest
        @CsvSource({"国, 1", "国, 2", "国, 3", "道, 1", "道, 2", "道, 3", "犬, 1", "犬, 2", "犬, 3", "玉, 1", "玉, 2", "玉, 3",
                "未, 1", "未, 2", "未, 3", "末, 1", "末, 2", "末, 3", "土, 1", "土, 2", "土, 3", "士, 1", "士, 2", "士, 3",
                "衛, 1", "衛, 2", "衛, 3", "語, 1", "語, 2", "語, 3"})
        void mildlySloppyHandwritingPasses(String kanji, long seed) {
            var s = TestDrawings.sloppy(TestDrawings.strokes(kanji), 2, 4, 0.10, 1.5, seed);
            CheckResult r = check(kanji, TestDrawings.render(s));
            assertTrue(r.pass(), () -> kanji + " seed " + seed + " failed: " + r.feedback());
        }

        @Test
        void slightlyOffCentreAndSizedIsCorrectedWithoutClamping() {
            var s = TestDrawings.shift(TestDrawings.scale(TestDrawings.strokes("語"), 0.93), strokes("1 2 3 4 5 6 7 8 9 10 11 12 13 14"), 3, -2);
            CheckResult r = check("語", TestDrawings.render(s));
            assertTrue(r.pass(), () -> "failed: " + r.feedback());
            assertFalse(r.alignment().anyClamped());
            assertEquals(1 / 0.93, r.alignment().transform().scale(), 0.03);
        }
    }

    /**
     * Synthetic errors. Clear errors (a missing stroke, a stray mark, a dot written the wrong way)
     * must FAIL. Subtle ones (a stroke 30% too short or long, a length confusion such as 未 and
     * 末, a component out of place or proportion) may be FAIL or UNSURE, but must never PASS:
     * their size overlaps the natural variation of real handwriting.
     */
    @Nested
    class Errors {
        @ParameterizedTest(name = "{0} without stroke {1} -> {2}")
        @CsvSource({"犬, 4, 丶", "玉, 5, 丶", "国, 8, 囗", "道, 12, ⻌", "衛, 16, 行#2", "未, 1, stroke 1", "語, 14, 吾"})
        void droppedStroke(String kanji, int stroke, String component) {
            CheckResult r = check(kanji, TestDrawings.render(TestDrawings.drop(TestDrawings.strokes(kanji), stroke)));
            assertEquals(Verdict.FAIL, r.verdict(), () -> r.feedback().toString());
            assertEquals(component, r.worst().label());
            assertEquals(stroke, worstStroke(r));
        }

        // 30% short (40% for 語's turning stroke 10, ㇕c, which snapping absorbs at 30%). A stroke
        // this short still leaves part of it uncovered, which is flagged. Not listed: strokes whose
        // cut end is absorbed by the component's placement (道's ⻌ sweep, 衛's long hooked
        // vertical): one stroke 30% off that does not make another character may pass, as real
        // handwriting varies stroke length that much (the user's decision, 2026-11-24).
        @ParameterizedTest(name = "{0} stroke {1} cut to {3} -> {2}")
        @CsvSource({"国, 2, 囗, 0.7", "犬, 2, 大, 0.7", "玉, 4, 王, 0.7", "未, 3, 木, 0.7", "末, 3, 木, 0.7",
                "土, 3, 土, 0.7", "士, 1, 士, 0.7", "語, 10, 吾, 0.6"})
        void shortenedStroke(String kanji, int stroke, String component, double keep) {
            CheckResult r = check(kanji, TestDrawings.render(TestDrawings.shorten(TestDrawings.strokes(kanji), stroke, keep)));
            assertNotEquals(Verdict.PASS, r.verdict(), () -> r.feedback().toString());
            assertEquals(component, r.worst().label());
            assertEquals(stroke, worstStroke(r));
        }

        // One stroke drawn 30% too long without turning the character into another (末's long top
        // line drawn longer still; 未's short one drawn longer but still the shorter of the two) is
        // within real handwriting's variation, so it may pass; it is never a definite error. The
        // ones that do make another character (未 for 末, 士 for 土) are lengthConfusablesFail.
        @ParameterizedTest(name = "{0} stroke {1} 30% too long is not a definite error")
        @CsvSource({"未, 1", "末, 1", "土, 1", "士, 1", "犬, 1", "玉, 1"})
        void extendedStrokeIsNotADefiniteError(String kanji, int stroke) {
            CheckResult r = check(kanji, TestDrawings.render(TestDrawings.extend(TestDrawings.strokes(kanji), stroke, 0.3)));
            assertNotEquals(Verdict.FAIL, r.verdict(), () -> r.feedback().toString());
        }

        // 道 with all of 首 moved 10 units left is not listed: the alignment centres 首 and the
        // ⻌ shift that remains is within its allowance, so it may pass.
        @ParameterizedTest(name = "{0}: {2} moved by ({3}, {4})")
        @CsvSource({"語, 8 9 10 11 12 13 14, 吾, 10, 0", "語, 1 2 3 4 5 6 7, 言, 0, 10", "国, 3 4 5 6 7, 玉, 10, 0",
                "衛, 4 5 6 7 8 9 10 11 12 13, 韋, 0, 10"})
        void shiftedComponent(String kanji, String moved, String component, double dx, double dy) {
            CheckResult r = check(kanji, TestDrawings.render(TestDrawings.shift(TestDrawings.strokes(kanji), strokes(moved), dx, dy)));
            assertNotEquals(Verdict.PASS, r.verdict(), () -> r.feedback().toString());
            // Position in the grid is not scored, so "this component moved" and "the rest moved the
            // other way" are the same drawing: whichever is flagged, its placement must be clearly off.
            // A FAIL may instead come from its strokes snapping one by one onto the wrong ink.
            if (r.verdict() != Verdict.FAIL) {
                assertTrue(r.worst().placement().shift() > 5, () -> r.worst().label() + " " + r.worst().placement());
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"国", "道", "犬", "玉", "未", "土", "衛", "語"})
        void strayDot(String kanji) {
            var s = TestDrawings.strokes(kanji);
            Point spot = TestDrawings.emptySpot(s);
            CheckResult r = check(kanji, TestDrawings.render(s, TestDrawings.PEN, List.of(spot), 4.0));
            assertEquals(Verdict.FAIL, r.verdict(), () -> r.feedback().toString());
            assertEquals(1, r.strayMarks().size());
            StrayMark mark = r.strayMarks().get(0);
            assertEquals(spot.x(), mark.centroid().x(), 1.5);
            assertEquals(spot.y(), mark.centroid().y(), 1.5);
            assertEquals(mark.nearestComponent(), r.worst().label());
            assertEquals(1, r.worst().extraMarks());
        }

        /** Overall size is not scored: a correct but small drawing passes, with an informational note. */
        @ParameterizedTest(name = "{0} at {1}")
        @CsvSource({"国, 0.7", "道, 0.7", "犬, 0.7", "玉, 0.7", "未, 0.7", "末, 0.7", "土, 0.7", "士, 0.7", "衛, 0.7", "語, 0.7",
                "国, 0.6", "語, 0.6"})
        void smallDrawingPassesWithSizeNote(String kanji, double size) {
            CheckResult r = check(kanji, TestDrawings.render(TestDrawings.scale(TestDrawings.strokes(kanji), size)));
            assertTrue(r.pass(), () -> kanji + " failed: " + r.feedback());
            assertFalse(r.alignment().scaleClamped());
            assertEquals(1 / size, r.alignment().transform().scale(), 0.03);
            assertTrue(r.feedback().get(0).startsWith("Drawn smaller than the grid"), () -> r.feedback().toString());
        }

        @Test
        void largeDrawingPassesWithSizeNote() {
            CheckResult r = check("国", TestDrawings.render(TestDrawings.scale(TestDrawings.strokes("国"), 1.2)));
            assertTrue(r.pass(), () -> "failed: " + r.feedback());
            assertTrue(r.feedback().get(0).startsWith("Drawn larger than the grid"), () -> r.feedback().toString());
        }

        /** Strict grading takes a little off for the overall size; the other levels none. */
        @Test
        void sizeLowersTheScoreOnlySlightly() {
            var s = TestDrawings.strokes("語");
            BufferedImage full = TestDrawings.render(s);
            BufferedImage small = TestDrawings.render(TestDrawings.scale(s, 0.7), TestDrawings.PEN * 0.7, List.of(), 0);
            double lost = check("語", full).score() - check("語", small).score();
            assertTrue(lost > 0 && lost <= CheckerConfig.MAX_FRAMING_PENALTY + 0.02, () -> "lost " + lost);
            CheckerConfig medium = CheckerConfig.preset(CheckerConfig.Strictness.MEDIUM);
            assertEquals(CHECKER.check(full, "語", medium).score(), CHECKER.check(small, "語", medium).score(), 0.02);
        }

        /** Like size, position in the grid is not scored: an off-centre drawing passes, with a note. */
        @ParameterizedTest
        @CsvSource({"口, -14, 0, too far left", "口, 14, 0, too far right", "語, 0, -12, too high", "国, 0, 12, too low"})
        void offCentreDrawingPassesWithNote(String kanji, double dx, double dy, String message) {
            var s = TestDrawings.strokes(kanji);
            var all = java.util.stream.IntStream.rangeClosed(1, s.size()).boxed().collect(Collectors.toSet());
            CheckResult r = check(kanji, TestDrawings.render(TestDrawings.shift(s, all, dx, dy)));
            assertTrue(r.pass(), () -> r.feedback().toString());
            assertTrue(r.feedback().stream().anyMatch(f -> f.startsWith("Note: drawn off-centre") && f.contains(message)),
                    () -> r.feedback().toString());
        }

        /** A look-alike radical (彳 written for 亻: 往 drawn for 住) is not accepted. */
        @Test
        void wrongRadicalIsNotAccepted() {
            CheckResult r = check("住", TestDrawings.render(TestDrawings.strokes("往")));
            assertNotEquals(Verdict.PASS, r.verdict(), () -> r.feedback().toString());
        }

        /** The right radical is not suspected of being a look-alike. */
        @Test
        void rightRadicalIsNotSuspected() {
            CheckResult r = check("住", TestDrawings.render(TestDrawings.strokes("住")));
            assertEquals(Verdict.PASS, r.verdict(), () -> r.feedback().toString());
            assertFalse(r.feedback().stream().anyMatch(f -> f.contains("looks like")), () -> r.feedback().toString());
        }

        /**
         * Length confusions are subtle errors: never PASS (usually UNSURE). 天 drawn for 夫 stops
         * its sweep at the top line instead of carrying on above it. (夫 drawn for 天 is not caught:
         * KanjiVG's 天 starts its sweep 3.4 units below the top line, so the sweep carrying on
         * above it reads as a small run-on, not as drawing through a stroke it should stop at.)
         */
        @ParameterizedTest(name = "{0} drawn, {1} expected")
        @CsvSource({"末, 未", "未, 末", "士, 土", "土, 士", "天, 夫"})
        void lengthConfusablesFail(String drawn, String expected) {
            CheckResult r = check(expected, TestDrawings.render(TestDrawings.strokes(drawn)));
            assertNotEquals(Verdict.PASS, r.verdict(), () -> drawn + " was accepted as " + expected);
        }

        @ParameterizedTest(name = "{0} drawn, {1} expected (distance scoring)")
        @CsvSource({"末, 未", "未, 末", "士, 土", "土, 士"})
        void lengthConfusablesFailWithDistanceScoring(String drawn, String expected) {
            CheckerConfig distance = CONFIG.toBuilder().scoring(CheckerConfig.Scoring.DISTANCE).build();
            CheckResult r = CHECKER.check(TestDrawings.render(TestDrawings.strokes(drawn)), expected, distance);
            assertNotEquals(Verdict.PASS, r.verdict(), () -> drawn + " was accepted as " + expected);
        }

        @Test
        void emptyDrawingFails() {
            CheckResult r = check("国", TestDrawings.render(List.of()));
            assertEquals(Verdict.FAIL, r.verdict(), () -> r.feedback().toString());
            assertEquals(0, r.score());
            assertEquals("No ink found in the image.", r.feedback().get(0));
        }
    }

    @Nested
    class Snapping {
        /** A single stroke a little out of place (about 6% of the square) is ordinary handwriting. */
        @ParameterizedTest(name = "{0}: stroke {1} moved by ({2}, {3})")
        @CsvSource({"土, 2, 7, 0", "三, 2, 0, 5", "語, 9, -5, 0"})
        void smallStrokeOffsetIsAccepted(String kanji, String stroke, double dx, double dy) {
            var s = TestDrawings.shift(TestDrawings.strokes(kanji), strokes(stroke), dx, dy);
            CheckResult r = check(kanji, TestDrawings.render(s));
            assertTrue(r.pass(), () -> r.feedback().toString());
        }

        @Test
        void missingStrokeCannotBorrowNeighboursInk() {
            // 語's stroke 3 sits between strokes 2 and 4; it must not snap onto their ink.
            CheckResult r = check("語", TestDrawings.render(TestDrawings.drop(TestDrawings.strokes("語"), 3)));
            assertEquals(Verdict.FAIL, r.verdict(), () -> r.feedback().toString());
            assertEquals("言", r.worst().label());
            assertTrue(r.worst().strokes().get(2).coverage() < 0.5, () -> r.worst().strokes().toString());
        }

        @Test
        void componentDrawnOutOfPlaceIsReportedAsPlacement() {
            var s = TestDrawings.shift(TestDrawings.strokes("語"), strokes("8 9 10 11 12 13 14"), 10, 0);
            CheckResult r = check("語", TestDrawings.render(s));
            // Either 吾 moved right or 言 moved left: both describe this drawing (position is not scored).
            assertTrue(r.worst().placement().shift() > 5, () -> "placement " + r.worst().placement());
            String side = r.worst().label().equals("吾") ? "too far right" : "too far left";
            assertTrue(r.feedback().stream().anyMatch(f -> f.startsWith(r.worst().label() + ": placed about") && f.contains(side)),
                    () -> r.feedback().toString());
        }

        @Test
        void distanceScoringIsStillAvailable() {
            CheckerConfig distance = CONFIG.toBuilder().scoring(CheckerConfig.Scoring.DISTANCE).build();
            CheckResult r = CHECKER.check(TestDrawings.render(TestDrawings.strokes("語")), "語", distance);
            assertTrue(r.pass());
            assertEquals(1, r.worst().placement().score());
        }
    }

    @Nested
    class Strictness {
        private CheckResult checkAt(CheckerConfig.Strictness level, String kanji, BufferedImage drawing) {
            return CHECKER.check(drawing, kanji, CheckerConfig.preset(level));
        }

        @ParameterizedTest
        @EnumSource(CheckerConfig.Strictness.class)
        void everyLevelPassesACleanDrawing(CheckerConfig.Strictness level) {
            CheckResult r = checkAt(level, "語", TestDrawings.render(TestDrawings.strokes("語")));
            assertEquals(Verdict.PASS, r.verdict(), () -> level + ": " + r.feedback());
        }

        @Test
        void strictIsTheDefault() {
            assertEquals(CheckerConfig.defaults(), CheckerConfig.preset(CheckerConfig.Strictness.STRICT));
            assertEquals(CheckerConfig.Strictness.STRICT, CheckerConfig.defaults().strictness());
        }

        @ParameterizedTest(name = "{0} drawn, {1} expected")
        @CsvSource({"士, 土", "土, 士", "末, 未", "未, 末"})
        void relaxedIgnoresLength(String drawn, String expected) {
            BufferedImage img = TestDrawings.render(TestDrawings.strokes(drawn));
            assertEquals(Verdict.PASS, checkAt(CheckerConfig.Strictness.RELAXED, expected, img).verdict());
            assertNotEquals(Verdict.PASS, checkAt(CheckerConfig.Strictness.STRICT, expected, img).verdict());
        }

        @Test
        void relaxedIgnoresWhereRadicalsSit() {
            // 語 with all of 吾 moved 10 units right.
            var s = TestDrawings.shift(TestDrawings.strokes("語"), strokes("8 9 10 11 12 13 14"), 10, 0);
            assertEquals(Verdict.PASS, checkAt(CheckerConfig.Strictness.RELAXED, "語", TestDrawings.render(s)).verdict());
        }

        @ParameterizedTest
        @EnumSource(CheckerConfig.Strictness.class)
        void clearErrorsFailAtEveryLevel(CheckerConfig.Strictness level) {
            var strokes = TestDrawings.strokes("語");
            assertEquals(Verdict.FAIL, checkAt(level, "語", TestDrawings.render(TestDrawings.drop(strokes, 3))).verdict());
            CheckResult dot = checkAt(level, "犬", TestDrawings.render(TestDrawings.strokes("大")));
            assertEquals(Verdict.FAIL, dot.verdict(), () -> level + " accepted 大 for 犬");
            Point spot = TestDrawings.emptySpot(strokes);
            assertEquals(Verdict.FAIL, checkAt(level, "語", TestDrawings.render(strokes, TestDrawings.PEN, List.of(spot), 4.0)).verdict());
        }

        @Test
        void strictScoresOverallSizeSlightly() {
            BufferedImage small = TestDrawings.render(TestDrawings.scale(TestDrawings.strokes("語"), 0.6));
            CheckResult strict = checkAt(CheckerConfig.Strictness.STRICT, "語", small);
            CheckResult medium = checkAt(CheckerConfig.Strictness.MEDIUM, "語", small);
            assertTrue(strict.score() < medium.score() - 0.03, () -> strict.score() + " vs " + medium.score());
            assertTrue(medium.score() - strict.score() <= CheckerConfig.MAX_FRAMING_PENALTY + 1e-9);
            assertEquals(Verdict.PASS, strict.verdict(), () -> strict.feedback().toString());
        }

        @Test
        void strictScoresTiltSlightly() {
            var tilted = rotate(TestDrawings.strokes("語"), 4);
            CheckResult strict = checkAt(CheckerConfig.Strictness.STRICT, "語", TestDrawings.render(tilted));
            CheckResult medium = checkAt(CheckerConfig.Strictness.MEDIUM, "語", TestDrawings.render(tilted));
            assertTrue(strict.score() < medium.score(), () -> strict.score() + " vs " + medium.score());
            assertEquals(Verdict.PASS, strict.verdict(), () -> strict.feedback().toString());
        }

        private List<List<Point>> rotate(List<List<Point>> strokes, double degrees) {
            double a = Math.toRadians(degrees), c = 54.5;
            return strokes.stream().map(s -> s.stream().map(p -> new Point(
                    c + Math.cos(a) * (p.x() - c) - Math.sin(a) * (p.y() - c),
                    c + Math.sin(a) * (p.x() - c) + Math.cos(a) * (p.y() - c))).toList()).toList();
        }
    }

    @Nested
    class Japanese {
        private final CheckerConfig ja = CONFIG.toBuilder().language(CheckerConfig.Language.JA).build();

        @Test
        void missingStrokeFeedbackIsJapanese() {
            CheckResult r = CHECKER.check(TestDrawings.render(TestDrawings.drop(TestDrawings.strokes("犬"), 4)), "犬", ja);
            assertTrue(r.feedback().contains("丶：4画目が足りないか短すぎます（0%）。"), () -> r.feedback().toString());
        }

        @Test
        void unnamedComponentLabelsAreJapanese() {
            CheckResult r = CHECKER.check(TestDrawings.render(TestDrawings.drop(TestDrawings.strokes("未"), 1)), "未", ja);
            assertTrue(r.feedback().get(0).startsWith("1画目："), () -> r.feedback().toString());
        }

        @Test
        void sizeNoteIsJapanese() {
            var s = TestDrawings.scale(TestDrawings.strokes("国"), 0.7);
            CheckResult r = CHECKER.check(TestDrawings.render(s), "国", ja);
            assertTrue(r.feedback().get(0).startsWith("マスより小さく書かれています"), () -> r.feedback().toString());
        }

        @Test
        void messagesTranslateLabels() {
            Messages m = Messages.of(CheckerConfig.Language.JA);
            assertEquals("3画目", m.label("stroke 3"));
            assertEquals("2〜4画目", m.label("strokes 2-4"));
            assertEquals("言", m.label("言"));
            assertEquals("合格", m.verdict(Verdict.PASS));
            assertTrue(m.verdict(Verdict.UNSURE).startsWith("要確認"));
        }
    }

    @Nested
    class Output {
        @Test
        void componentsAreSortedWorstFirst() {
            CheckResult r = check("語", TestDrawings.render(TestDrawings.drop(TestDrawings.strokes("語"), 14)));
            for (int i = 1; i < r.components().size(); i++) {
                assertTrue(r.components().get(i - 1).score() <= r.components().get(i).score());
            }
        }

        @Test
        void perStrokeCoverageIsReported() {
            CheckResult r = check("犬", TestDrawings.render(TestDrawings.drop(TestDrawings.strokes("犬"), 4)));
            ComponentScore dot = r.worst();
            assertEquals(1, dot.strokes().size());
            assertEquals(3, dot.strokes().get(0).strokeIndex());
            assertEquals(0, dot.strokes().get(0).coverage(), 1e-9);
            assertTrue(r.feedback().stream().anyMatch(f -> f.startsWith("丶: stroke 4 is missing")), () -> r.feedback().toString());
        }

        @Test
        void overlayIsRendered() {
            CheckResult r = check("国", TestDrawings.render(TestDrawings.drop(TestDrawings.strokes("国"), 8)));
            assertTrue(r.overlay().getWidth() >= 600);
            assertTrue(r.overlay().getHeight() > r.overlay().getWidth(), "legend below the cell");
        }

        @Test
        void componentDepthIsConfigurable() {
            BufferedImage img = TestDrawings.render(TestDrawings.strokes("語"));
            CheckerConfig deeper = CONFIG.toBuilder().componentDepth(2).build();
            List<String> labels = CHECKER.check(img, "語", deeper).components().stream().map(ComponentScore::label).sorted().toList();
            assertEquals(List.of("五", "口", "口", "strokes 1-4").stream().sorted().toList(), labels);
        }

        @Test
        void referenceOverrideUsesAlternateCharacter() {
            CheckerConfig config = CONFIG.toBuilder().referenceOverrides(Map.of("未", "末")).build();
            CheckResult r = CHECKER.check(TestDrawings.render(TestDrawings.strokes("末")), "未", config);
            assertEquals("末", r.referenceKanji());
            assertTrue(r.pass());
        }

        @Test
        void variantFormIsAccepted() {
            // 日 written in KanjiVG's Kaisho (regular script) form.
            var kaisho = new ReferenceRepository().getVariant("日", "Kaisho").orElseThrow()
                    .strokes().stream().map(s -> s.polyline()).toList();
            CheckResult r = check("日", TestDrawings.render(kaisho));
            // Snapping may fit it to the standard form just as well; either form may be reported.
            assertEquals(Verdict.PASS, r.verdict(), () -> r.feedback().toString());
        }

        @Test
        void standardFormIsReportedWithoutVariant() {
            CheckResult r = check("語", TestDrawings.render(TestDrawings.strokes("語")));
            assertEquals(Verdict.PASS, r.verdict(), () -> r.feedback().toString());
            assertNull(r.referenceVariant());
        }

        @ParameterizedTest
        @ValueSource(strings = {"お", "あ", "ア", "ツ", "ｱ"})
        void kanaAreNotChecked(String kana) {
            var img = TestDrawings.render(TestDrawings.strokes("語"));
            assertThrows(IllegalArgumentException.class, () -> CHECKER.check(img, kana, CONFIG));
        }

        @Test
        void unknownKanjiIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> check("", TestDrawings.render(List.of())));
        }
    }
}
