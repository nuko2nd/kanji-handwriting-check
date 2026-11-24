package kanji.handwriting.check;

import java.util.Optional;
import kanji.handwriting.check.model.Bounds;
import kanji.handwriting.check.model.ReferenceComponent;
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
        System.out.println("  components:");
        for (ReferenceComponent c : ref.components()) {
            print(c, "    ");
        }
    }

    private static void print(ReferenceComponent c, String indent) {
        StringBuilder line = new StringBuilder(indent).append(c.element() == null ? "(unnamed)" : c.element());
        if (c.position() != null) line.append("  position=").append(c.position());
        if (c.radical() != null) line.append("  radical=").append(c.radical());
        if (c.part() != null) line.append("  part=").append(c.part());
        line.append("  strokes=").append(c.strokes().stream().map(s -> s.index() + 1).toList());
        Bounds b = c.bounds();
        line.append(String.format("  box=(%.0f,%.0f)-(%.0f,%.0f)", b.minX(), b.minY(), b.maxX(), b.maxY()));
        System.out.println(line);
        for (ReferenceComponent child : c.children()) {
            print(child, indent + "  ");
        }
    }
}
