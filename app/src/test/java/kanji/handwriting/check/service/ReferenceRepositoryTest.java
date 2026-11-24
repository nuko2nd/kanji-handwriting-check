package kanji.handwriting.check.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import kanji.handwriting.check.model.Bounds;
import kanji.handwriting.check.model.Point;
import kanji.handwriting.check.model.ReferenceComponent;
import kanji.handwriting.check.model.ReferenceKanji;
import kanji.handwriting.check.model.ReferenceStroke;

class ReferenceRepositoryTest {
    private static final double EPS = 1e-9;

    private static void assertPoint(double x, double y, Point actual) {
        assertAll(
                () -> assertEquals(x, actual.x(), EPS, "x of " + actual),
                () -> assertEquals(y, actual.y(), EPS, "y of " + actual));
    }

    @Nested
    class Get {
        private final ReferenceRepository repo = new ReferenceRepository();

        @Test
        void loadsStrokesInOrder() {
            ReferenceKanji a = repo.get("あ").orElseThrow();

            assertEquals("あ", a.kanji());
            assertEquals(3, a.strokes().size());
            for (int i = 0; i < a.strokes().size(); i++) {
                assertEquals(i, a.strokes().get(i).index());
            }
        }

        @Test
        void flattensFirstStrokeOfA() {
            // kvg:03042-s1: M31.01,33 followed by three relative cubic curves
            List<Point> polyline = repo.get("あ").orElseThrow().strokes().get(0).polyline();

            assertEquals(1 + 3 * 8, polyline.size());
            assertPoint(31.01, 33, polyline.get(0));
            assertPoint(72.38, 30, polyline.get(polyline.size() - 1));
        }

        @Test
        void readsStrokeType() {
            List<ReferenceStroke> strokes = repo.get("⺉").orElseThrow().strokes(); // ⺉
            assertEquals("㇑", strokes.get(0).kvgType());
            assertEquals("㇚", strokes.get(1).kvgType());
        }

        @Test
        void strokeTypeIsNullWhenAbsent() {
            assertNull(repo.get("あ").orElseThrow().strokes().get(0).kvgType());
        }

        @Test
        void pointsStayInsideKanjiVgSpace() {
            for (ReferenceStroke stroke : repo.get("あ").orElseThrow().strokes()) {
                for (Point p : stroke.polyline()) {
                    assertTrue(p.x() >= 0 && p.x() <= 109 && p.y() >= 0 && p.y() <= 109, p::toString);
                }
            }
        }

        @Test
        void missingFileReturnsEmpty() {
            assertTrue(repo.get("").isEmpty()); // private-use character, never in KanjiVG
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"ab", "あい"})
        void notExactlyOneCharacterReturnsEmpty(String input) {
            assertTrue(repo.get(input).isEmpty());
        }
    }

    @Nested
    class Kanji {
        private final ReferenceRepository repo = new ReferenceRepository();

        @ParameterizedTest(name = "{0} has {1} strokes")
        @CsvSource({
            "一, 1",
            "入, 2",
            "土, 3",
            "字, 6",
            "永, 5",
            "高, 10",
            "漢, 13",
            "鬱, 29",
        })
        void strokeCounts(String kanji, int expected) {
            ReferenceKanji ref = repo.get(kanji).orElseThrow();
            assertEquals(kanji, ref.kanji());
            assertEquals(expected, ref.strokes().size());
        }

        @Test
        void ichiIsOneLeftToRightStroke() {
            // M11,54.25 then relative curves summing to (+85.88, -4.25)
            ReferenceStroke stroke = repo.get("一").orElseThrow().strokes().get(0);
            assertEquals("㇐", stroke.kvgType());
            assertEquals(1 + 3 * 8, stroke.polyline().size());
            assertPoint(11, 54.25, stroke.polyline().get(0));
            assertPoint(96.88, 50, stroke.polyline().get(stroke.polyline().size() - 1));
        }

        @Test
        void eiStrokeTypesInOrder() {
            // 永 is the classic "eight principles" character
            List<String> types = repo.get("永").orElseThrow().strokes().stream()
                    .map(ReferenceStroke::kvgType).toList();
            assertEquals(List.of("㇔", "㇆/㇚", "㇇", "㇒", "㇏"), types);
        }

        @Test
        void kanStrokeTypesInOrder() {
            List<String> types = repo.get("漢").orElseThrow().strokes().stream()
                    .map(ReferenceStroke::kvgType).toList();
            assertEquals(List.of("㇔", "㇔", "㇀", "㇐", "㇑a", "㇑a", "㇑", "㇕b", "㇐b", "㇐", "㇐", "㇒", "㇏"),
                    types);
        }

        @Test
        void utsuLastStroke() {
            // kvg:09b31-s29: M91.3,79 ... ending at (58.07, 99) after absolute C
            List<ReferenceStroke> strokes = repo.get("鬱").orElseThrow().strokes();
            ReferenceStroke last = strokes.get(28);
            assertEquals(28, last.index());
            assertEquals("㇒", last.kvgType());
            assertPoint(91.3, 79, last.polyline().get(0));
            assertPoint(58.07, 99, last.polyline().get(last.polyline().size() - 1));
        }

        @Test
        void sharpStrokeGoesTopRightToBottomLeft() {
            // 入 stroke 1 is ㇒: it should end lower and further left than it starts
            List<Point> pts = repo.get("入").orElseThrow().strokes().get(0).polyline();
            Point start = pts.get(0), end = pts.get(pts.size() - 1);
            assertTrue(end.x() < start.x() && end.y() > start.y(), start + " -> " + end);
        }

        @Test
        void supplementaryPlaneCharacter() {
            // 𠮟 (U+20B9F) is a surrogate pair in Java; its path data also uses spaces as separators
            String shitsu = new String(Character.toChars(0x20B9F));
            ReferenceKanji ref = repo.get(shitsu).orElseThrow();
            assertEquals(5, ref.strokes().size());
            assertPoint(14.75, 38.79, ref.strokes().get(0).polyline().get(0));
            assertPoint(43.310675, 48.910552, ref.strokes().get(3).polyline().get(0));
        }
    }

    @Nested
    class Components {
        private final ReferenceRepository repo = new ReferenceRepository();

        private ReferenceComponent component(String kanji, int... path) {
            List<ReferenceComponent> level = repo.get(kanji).orElseThrow().components();
            ReferenceComponent c = null;
            for (int i : path) {
                c = level.get(i);
                level = c.children();
            }
            return c;
        }

        private static List<Integer> strokeNumbers(ReferenceComponent c) {
            return c.strokes().stream().map(s -> s.index() + 1).toList();
        }

        @Test
        void kanIsWaterRadicalBesideRightPart() {
            List<ReferenceComponent> top = repo.get("漢").orElseThrow().components();
            assertEquals(2, top.size());

            ReferenceComponent water = top.get(0);
            assertEquals("氵", water.element());
            assertEquals("水", water.original());
            assertTrue(water.variant());
            assertEquals("left", water.position());
            assertEquals("general", water.radical());
            assertEquals(List.of(1, 2, 3), strokeNumbers(water));
            assertTrue(water.children().isEmpty());

            ReferenceComponent right = top.get(1);
            assertNull(right.element());
            assertEquals("right", right.position());
            assertEquals(List.of(4, 5, 6, 7, 8, 9, 10, 11, 12, 13), strokeNumbers(right));
            assertTrue(water.bounds().maxX() < right.bounds().minX(), "氵 should be left of the right part");
        }

        @Test
        void nestedComponents() {
            // 漢 → right part → bottom part → 夫 → [unnamed ㇐, 大]
            ReferenceComponent grass = component("漢", 1, 0);
            assertEquals("艹", grass.element());
            assertEquals("top", grass.position());

            ReferenceComponent fu = component("漢", 1, 1, 1);
            assertEquals("夫", fu.element());
            assertEquals(2, fu.children().size());
            assertNull(fu.children().get(0).element());
            assertEquals(List.of(10), strokeNumbers(fu.children().get(0)));
            assertEquals("大", fu.children().get(1).element());
        }

        @Test
        void enclosingSplitComponentIsMerged() {
            // 囗 encloses 或: KanjiVG stores it as part 1 (strokes 1-2) and part 2 (stroke 11)
            ReferenceComponent enclosure = component("國", 0);
            assertEquals("囗", enclosure.element());
            assertEquals("kamae", enclosure.position());
            assertNull(enclosure.part());
            assertEquals(List.of(1, 2, 11), strokeNumbers(enclosure));
            assertEquals(2, repo.get("國").orElseThrow().components().size());
        }

        @Test
        void touchingPiecesCountAsEnclosing() {
            // 匚's top stroke touches its ㇗ stroke, so the two pieces are one enclosure around 斤
            List<ReferenceComponent> top = repo.get("匠").orElseThrow().components();
            assertEquals(2, top.size());
            assertEquals("匚", top.get(0).element());
            assertNull(top.get(0).part());
            assertEquals(List.of(1, 6), strokeNumbers(top.get(0)));
        }

        @Test
        void sideBySideSplitComponentStaysSplit() {
            // 行 around 圭 in 街: left piece 彳, right piece 亍, each its own component
            List<ReferenceComponent> top = repo.get("街").orElseThrow().components();
            assertEquals(List.of("行", "圭", "行"), top.stream().map(ReferenceComponent::element).toList());

            ReferenceComponent left = top.get(0), right = top.get(2);
            assertEquals(Integer.valueOf(1), left.part());
            assertEquals("left", left.position());
            assertEquals(List.of(1, 2, 3), strokeNumbers(left));
            assertEquals("彳", left.children().get(0).element());

            assertEquals(Integer.valueOf(2), right.part());
            assertEquals("right", right.position());
            assertEquals(List.of(10, 11, 12), strokeNumbers(right));
        }

        @Test
        void stackedSplitComponentStaysSplit() {
            // 二 in 五: top line and bottom line with the middle strokes between
            List<ReferenceComponent> top = repo.get("五").orElseThrow().components();
            assertEquals(3, top.size());
            assertEquals("top", top.get(0).position());
            assertEquals(List.of(1), strokeNumbers(top.get(0)));
            assertEquals("bottom", top.get(2).position());
            assertEquals(List.of(4), strokeNumbers(top.get(2)));
        }

        @Test
        void threePiecesGetMiddlePosition() {
            // 川 in 州 is split into three around the dots
            List<String> positions = repo.get("州").orElseThrow().components().stream()
                    .filter(c -> "川".equals(c.element())).map(ReferenceComponent::position).toList();
            assertEquals(List.of("left", "middle", "right"), positions);
        }

        @Test
        void interlockingPiecesKeepPartNumbers() {
            // 千 in 乗: piece 1 at the top level, piece 2 nested inside 木
            ReferenceComponent first = component("乗", 0);
            assertEquals("千", first.element());
            assertEquals(Integer.valueOf(1), first.part());
            assertEquals(List.of(1, 2), strokeNumbers(first));

            ReferenceComponent tree = component("乗", 2);
            assertEquals("木", tree.element());
            ReferenceComponent second = tree.children().get(1);
            assertEquals("千", second.element());
            assertEquals(Integer.valueOf(2), second.part());
            assertEquals(List.of(7), strokeNumbers(second));
        }

        @Test
        void looseStrokesGetUnnamedComponent() {
            List<ReferenceComponent> top = repo.get("万").orElseThrow().components();
            assertEquals(2, top.size());
            assertEquals("一", top.get(0).element());
            assertNull(top.get(1).element());
            assertEquals(List.of(2, 3), strokeNumbers(top.get(1)));
        }

        @Test
        void sameElementTwiceIsNotMerged() {
            // 圭 is 土 above 土: two whole components, not one split component
            ReferenceComponent kei = component("街", 1); // [行 left, 圭, 行 right]
            assertEquals("圭", kei.element());
            assertEquals(List.of("top", "bottom"), kei.children().stream().map(ReferenceComponent::position).toList());
            assertEquals(List.of(4, 5, 6), strokeNumbers(kei.children().get(0)));
            assertEquals(List.of(7, 8, 9), strokeNumbers(kei.children().get(1)));
        }

        @Test
        void differentRadicalClassifications() {
            // 問: 門 is the Nelson radical, 口 the traditional one
            List<ReferenceComponent> top = repo.get("問").orElseThrow().components();
            assertEquals("nelson", top.get(0).radical());
            assertEquals("tradit", top.get(1).radical());
        }

        @ParameterizedTest
        @ValueSource(strings = {"一", "あ", "A"})
        void undividedCharacterIsItsOwnComponent(String ch) {
            ReferenceKanji ref = repo.get(ch).orElseThrow();
            assertEquals(1, ref.components().size());
            ReferenceComponent only = ref.components().get(0);
            assertEquals(ch, only.element());
            assertEquals(ref.strokes(), only.strokes());
            assertTrue(only.children().isEmpty());
        }

        @Test
        void componentStrokesAreTheCharactersStrokes() {
            ReferenceKanji ref = repo.get("漢").orElseThrow();
            assertTrue(ref.components().get(0).strokes().get(0) == ref.strokes().get(0));
        }
    }

    /** Every KanjiVG file shipped in resources must load and look sane. */
    @Test
    void everyBundledFileLoads() throws Exception {
        Path dir = Path.of(ReferenceRepository.class.getResource("/kanjivg").toURI());
        Pattern strokeId = Pattern.compile("id=\"kvg:[0-9a-f]+-s\\d+\"");
        ReferenceRepository repo = new ReferenceRepository();
        List<String> failures = new ArrayList<>();
        int checked = 0;

        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                String name = file.getFileName().toString();
                String ch = new String(Character.toChars(Integer.parseInt(name.replace(".svg", ""), 16)));
                long expectedStrokes = strokeId.matcher(Files.readString(file)).results().count();
                try {
                    Optional<ReferenceKanji> ref = repo.get(ch);
                    if (ref.isEmpty()) {
                        failures.add(name + ": not found");
                    } else if (ref.get().strokes().size() != expectedStrokes) {
                        failures.add(name + ": " + ref.get().strokes().size() + " strokes, expected " + expectedStrokes);
                    } else {
                        checkComponents(name, "top level", ref.get().strokes(), ref.get().components(), failures);
                        if (ref.get().components().isEmpty()) {
                            failures.add(name + ": no components");
                        }
                        for (ReferenceStroke s : ref.get().strokes()) {
                            if (s.polyline().size() < 2) {
                                failures.add(name + " stroke " + s.index() + ": fewer than 2 points");
                            }
                            for (Point p : s.polyline()) {
                                if (!(p.x() >= MIN && p.x() <= MAX && p.y() >= MIN && p.y() <= MAX)) {
                                    failures.add(name + " stroke " + s.index() + ": out of bounds " + p);
                                    break;
                                }
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    failures.add(name + ": " + e);
                }
                checked++;
            }
        }

        assertTrue(checked > 6000, "only " + checked + " files found");
        assertTrue(failures.isEmpty(), failures.size() + " failures, e.g. "
                + failures.subList(0, Math.min(20, failures.size())));
    }

    /**
     * Components must cover their parent's strokes exactly once, reuse the character's stroke
     * objects, list strokes in order, and have bounds that contain every stroke point.
     */
    private static void checkComponents(String file, String where, List<ReferenceStroke> parent,
                                        List<ReferenceComponent> components, List<String> failures) {
        if (components.isEmpty()) {
            return;
        }
        List<ReferenceStroke> union = new ArrayList<>();
        components.forEach(c -> union.addAll(c.strokes()));
        union.sort(Comparator.comparingInt(ReferenceStroke::index));
        if (!union.equals(parent)) {
            failures.add(file + " " + where + ": components do not cover the strokes exactly once");
        }
        for (ReferenceComponent c : components) {
            String label = c.element() == null ? "(unnamed)" : c.element();
            for (int i = 0; i < c.strokes().size(); i++) {
                ReferenceStroke s = c.strokes().get(i);
                if (i > 0 && s.index() <= c.strokes().get(i - 1).index()) {
                    failures.add(file + " " + label + ": strokes not in order");
                }
                if (parent.stream().noneMatch(p -> p == s)) {
                    failures.add(file + " " + label + ": stroke " + s.index() + " is not the parent's object");
                }
                for (Point p : s.polyline()) {
                    Bounds b = c.bounds();
                    if (p.x() < b.minX() || p.x() > b.maxX() || p.y() < b.minY() || p.y() > b.maxY()) {
                        failures.add(file + " " + label + ": bounds miss " + p);
                        break;
                    }
                }
            }
            checkComponents(file, label, c.strokes(), c.children(), failures);
        }
    }

    /** Allowed coordinate range: KanjiVG's 109x109 box. */
    private static final double MIN = 0, MAX = 109;

    @Nested
    class Flatten {
        @Test
        void absoluteLines() {
            List<Point> pts = ReferenceRepository.flatten("M10,20L30,40");
            assertEquals(2, pts.size());
            assertPoint(10, 20, pts.get(0));
            assertPoint(30, 40, pts.get(1));
        }

        @Test
        void relativeLinesHorizontalVerticalAndClose() {
            List<Point> pts = ReferenceRepository.flatten("m10,20l5,5h5v-5z");
            assertEquals(5, pts.size());
            assertPoint(10, 20, pts.get(0));
            assertPoint(15, 25, pts.get(1));
            assertPoint(20, 25, pts.get(2));
            assertPoint(20, 20, pts.get(3));
            assertPoint(10, 20, pts.get(4));
        }

        @Test
        void extraPairsAfterMoveAreLines() {
            List<Point> pts = ReferenceRepository.flatten("M0,0 10,0 10,10");
            assertEquals(3, pts.size());
            assertPoint(10, 10, pts.get(2));
        }

        @Test
        void compactNumbersWithoutSeparators() {
            List<Point> pts = ReferenceRepository.flatten("M1-2L.5.5");
            assertPoint(1, -2, pts.get(0));
            assertPoint(0.5, 0.5, pts.get(1));
        }

        @Test
        void cubicIsSampled() {
            List<Point> pts = ReferenceRepository.flatten("M0,0C0,10,10,10,10,0");
            assertEquals(9, pts.size());
            assertPoint(5, 7.5, pts.get(4)); // u = 0.5
            assertPoint(10, 0, pts.get(8));
        }

        @Test
        void smoothCubicReflectsPreviousControlPoint() {
            // Reflected first control point is (10,-10), so the second curve mirrors the first.
            List<Point> pts = ReferenceRepository.flatten("M0,0C0,10,10,10,10,0S20,-10,20,0");
            assertEquals(17, pts.size());
            assertPoint(15, -7.5, pts.get(12));
            assertPoint(20, 0, pts.get(16));
        }

        @Test
        void quadraticIsSampled() {
            List<Point> pts = ReferenceRepository.flatten("M0,0Q5,10,10,0");
            assertEquals(9, pts.size());
            assertPoint(5, 5, pts.get(4));
        }

        @Test
        void rejectsDataWithoutLeadingCommand() {
            assertThrows(IllegalArgumentException.class, () -> ReferenceRepository.flatten("10,10"));
        }

        @Test
        void rejectsArcs() {
            assertThrows(IllegalArgumentException.class,
                    () -> ReferenceRepository.flatten("M0,0A5,5,0,0,1,10,10"));
        }

        @Test
        void rejectsTruncatedData() {
            assertThrows(IllegalArgumentException.class, () -> ReferenceRepository.flatten("M0,0C1,1,2,2"));
        }
    }
}
