package kanji.handwriting.check;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import kanji.handwriting.check.checker.CheckResult;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.checker.KanjiChecker;
import kanji.handwriting.check.checker.Messages;
import kanji.handwriting.check.checker.score.ComponentScore;
import kanji.handwriting.check.checker.score.StrayMark;
import kanji.handwriting.check.service.ReferenceRepository;

/**
 * Command-line checker:
 *
 * <pre>{@code ./gradlew run --args="drawing.png 語 [overlay.png] [options]"}</pre>
 *
 * Prints the verdict, alignment, per-component scores (worst first) and feedback, and saves the
 * overlay image (by default next to the drawing, as {@code <name>-check.png}). Exits with 0 on
 * pass, 1 on fail, 3 when unsure (check manually), and 2 on bad input. Run without arguments for the options.
 */
public final class App {
    private static final String USAGE = """
            Usage: ./gradlew run --args="<image.png> <kanji> [overlay.png] [options]"
            Options (defaults in brackets):
              --lang en|ja          language of the results and overlay [en]
              --scoring snap|distance
                                    snap: fit each part and stroke onto the ink and score how far
                                    it had to move; distance: compare in place [snap]
              --tolerance N         full credit within N units of the reference [6]
              --falloff N           credit reaches 0 at N x tolerance; 1 = hard cutoff [1.6]
              --threshold N         pass score [0.8]
              --min-coverage N      every stroke must be at least this covered [0.9]
              --stray N             ink farther than N units from every stroke is extra [10]
              --depth N             component level to score, 1 = top level [1]""";

    private App() {}

    /**
     * Runs the checker.
     *
     * @param args image path, target kanji, optional overlay output path, then options
     */
    public static void main(String[] args) {
        List<String> positional = new ArrayList<>();
        CheckerConfig.Builder config = CheckerConfig.defaults().toBuilder();
        try {
            for (int i = 0; i < args.length; i++) {
                if (!args[i].startsWith("--")) {
                    positional.add(args[i]);
                    continue;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + args[i]);
                }
                String option = args[i], value = args[++i];
                switch (option) {
                    case "--lang" -> config.language(CheckerConfig.Language.valueOf(value.toUpperCase(Locale.ROOT)));
                    case "--scoring" -> config.scoring(CheckerConfig.Scoring.valueOf(value.toUpperCase(Locale.ROOT)));
                    case "--tolerance" -> config.tolerance(Double.parseDouble(value));
                    case "--falloff" -> config.creditFalloff(Double.parseDouble(value));
                    case "--threshold" -> config.passThreshold(Double.parseDouble(value));
                    case "--min-coverage" -> config.minStrokeCoverage(Double.parseDouble(value));
                    case "--stray" -> config.strayDistance(Double.parseDouble(value));
                    case "--depth" -> config.componentDepth(Integer.parseInt(value));
                    default -> throw new IllegalArgumentException("Unknown option " + option);
                }
            }
        } catch (IllegalArgumentException e) { // includes NumberFormatException and bad enum names
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
        }
        if (positional.size() < 2 || positional.size() > 3) {
            System.err.println(USAGE);
            System.exit(2);
        }
        Path image = Path.of(positional.get(0));
        String kanji = positional.get(1);
        Path overlay = positional.size() == 3 ? Path.of(positional.get(2)) : siblingOverlay(image);
        CheckerConfig cfg = config.build();
        Messages m = Messages.of(cfg.language());
        try {
            CheckResult r = new KanjiChecker(new ReferenceRepository()).check(image, kanji, cfg);
            print(r, m);
            ImageIO.write(r.overlay(), "png", overlay.toFile());
            System.out.println(m.overlaySaved(overlay.toAbsolutePath().toString()));
            System.exit(switch (r.verdict()) { case PASS -> 0; case FAIL -> 1; case UNSURE -> 3; });
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }
    }

    private static Path siblingOverlay(Path image) {
        String name = image.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return image.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + "-check.png");
    }

    private static void print(CheckResult r, Messages m) {
        System.out.println(m.headline(r.kanji(), r.verdict(), r.score()));
        if (!r.referenceKanji().equals(r.kanji())) {
            System.out.println(m.referenceUsed(r.referenceKanji()));
        }
        if (r.referenceVariant() != null) {
            System.out.println(m.variantUsed(r.referenceVariant()));
        }
        var t = r.alignment().transform();
        System.out.println(m.alignment(t.scale(), t.rotationDegrees(), t.tx(), t.ty(),
                r.alignment().rotationClamped()));
        System.out.println(m.componentsHeading());
        for (ComponentScore c : r.components()) {
            System.out.println(m.componentRow(m.label(c.label()), c.score(), c.coverage(), c.precision(), c.extraMarks()));
            var p = c.placement();
            if (p.score() < 0.995) {
                System.out.println(m.placementRow(p.score(), p.shift(), p.scale(), p.rotationDegrees()));
            }
            for (var part : c.parts()) {
                var d = part.placement();
                System.out.println("  " + m.label(part.label()) + " " + m.placementRow(d.score(), d.shift(), d.scale(),
                        d.rotationDegrees()).strip());
            }
            for (ComponentScore.StrokeScore s : c.strokes()) {
                var d = s.deformation();
                System.out.println(m.strokeRow(s.strokeIndex() + 1, s.kvgType() == null ? "" : s.kvgType(), s.score(),
                        s.coverage(), d.shift(), d.scale(), d.rotationDegrees()));
            }
        }
        for (StrayMark mark : r.strayMarks()) {
            System.out.println(m.extraMarkRow(mark.centroid().x(), mark.centroid().y(), mark.area(),
                    m.label(mark.nearestComponent())));
        }
        r.feedback().forEach(f -> System.out.println("  - " + f));
    }
}
