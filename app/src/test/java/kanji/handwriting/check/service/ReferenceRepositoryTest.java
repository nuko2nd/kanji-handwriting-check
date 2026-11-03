package kanji.handwriting.check.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

import kanji.handwriting.check.model.Point;
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
