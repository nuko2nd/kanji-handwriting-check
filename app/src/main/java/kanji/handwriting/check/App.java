package kanji.handwriting.check;

import java.util.Optional;
import kanji.handwriting.check.model.ReferenceKanji;
import kanji.handwriting.check.model.ReferenceStroke;
import kanji.handwriting.check.service.ReferenceRepository;

/**
 * Command-line entry point. For now it prints the KanjiVG reference for each character given:
 *
 * <pre>{@code ./gradlew run --args="漢 あ"}</pre>
 *
 * Replace or extend this once the web server exists.
 */
public class App {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: ./gradlew run --args=\"<character> [<character> ...]\"");
            return;
        }
        ReferenceRepository references = new ReferenceRepository();
        boolean allFound = true;
        for (String arg : args) {
            for (String ch : arg.codePoints().mapToObj(Character::toString).toList()) {
                Optional<ReferenceKanji> ref = references.get(ch);
                if (ref.isEmpty()) {
                    System.out.printf("%s (U+%04X): no KanjiVG data%n", ch, ch.codePointAt(0));
                    allFound = false;
                } else {
                    print(ref.get());
                }
            }
        }
        if (!allFound) {
            System.exit(1);
        }
    }

    private static void print(ReferenceKanji ref) {
        System.out.printf("%s (U+%04X): %d strokes%n",
                ref.kanji(), ref.kanji().codePointAt(0), ref.strokes().size());
        for (ReferenceStroke s : ref.strokes()) {
            var first = s.polyline().get(0);
            var last = s.polyline().get(s.polyline().size() - 1);
            System.out.printf("  %2d  %-6s (%6.2f, %6.2f) -> (%6.2f, %6.2f)%n",
                    s.index() + 1, s.kvgType() == null ? "-" : s.kvgType(),
                    first.x(), first.y(), last.x(), last.y());
        }
    }
}
