package kanji.handwriting.check.checker.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.checker.geometry.Polylines;
import kanji.handwriting.check.model.ReferenceComponent;
import kanji.handwriting.check.model.ReferenceKanji;
import kanji.handwriting.check.model.ReferenceStroke;
import kanji.handwriting.check.service.ReferenceRepository;

/**
 * Turns {@link ReferenceRepository} data into a {@link ReferenceShape}. Component rules (merging
 * enclosures, keeping side-by-side splits apart, wrapping loose strokes) are those of
 * {@link ReferenceRepository}, section 7. This class only picks the tree level to score and
 * samples the strokes.
 */
public final class ReferenceShapeLoader {
    private final ReferenceRepository repository;

    /**
     * Creates a loader.
     *
     * @param repository source of KanjiVG data
     */
    public ReferenceShapeLoader(ReferenceRepository repository) {
        this.repository = repository;
    }

    /**
     * Loads the reference for a character.
     *
     * @param kanji the target character
     * @param config uses {@code componentDepth}, {@code sampleStep} and {@code referenceOverrides}
     * @return the reference, or empty if KanjiVG has no data for the character (or its override)
     */
    public Optional<ReferenceShape> load(String kanji, CheckerConfig config) {
        String source = config.referenceOverrides().getOrDefault(kanji, kanji);
        return repository.get(source).map(ref -> build(kanji, ref, null, config));
    }

    /**
     * Loads every acceptable form of a character: KanjiVG's standard form first, then each bundled
     * variant (see {@link ReferenceRepository#variants}) whose shape differs from it. Variants
     * that only change the stroke order (KanjiVG's VtLst, HzFst, …) are skipped: the checker
     * ignores order, so they add nothing, and their stroke numbers would differ from the standard.
     *
     * @param kanji the target character
     * @param config as for {@link #load}
     * @return the forms; empty if KanjiVG has no data for the character (or its override)
     */
    public List<ReferenceShape> loadAll(String kanji, CheckerConfig config) {
        Optional<ReferenceShape> standard = load(kanji, config);
        if (standard.isEmpty()) {
            return List.of();
        }
        String source = standard.get().sourceKanji();
        List<ReferenceShape> out = new ArrayList<>(List.of(standard.get()));
        ReferenceKanji standardRef = repository.get(source).orElseThrow();
        for (String v : repository.variants(source)) {
            repository.getVariant(source, v)
                    .filter(ref -> !sameStrokes(standardRef, ref))
                    .ifPresent(ref -> out.add(build(kanji, ref, v, config)));
        }
        return List.copyOf(out);
    }

    /** Strokes this close (mean distance, reference units) count as the same stroke. */
    private static final double SAME_STROKE = 2;

    /** Whether two forms consist of the same stroke shapes, in any order. */
    static boolean sameStrokes(ReferenceKanji a, ReferenceKanji b) {
        if (a.strokes().size() != b.strokes().size()) {
            return false;
        }
        List<List<kanji.handwriting.check.model.Point>> left = new ArrayList<>();
        a.strokes().forEach(s -> left.add(Polylines.resample(s.polyline(), 2)));
        for (ReferenceStroke s : b.strokes()) {
            List<kanji.handwriting.check.model.Point> mine = Polylines.resample(s.polyline(), 2);
            int match = -1;
            for (int i = 0; i < left.size() && match < 0; i++) {
                if (meanDistance(mine, left.get(i)) < SAME_STROKE && meanDistance(left.get(i), mine) < SAME_STROKE) {
                    match = i;
                }
            }
            if (match < 0) {
                return false;
            }
            left.remove(match);
        }
        return true;
    }

    /** Mean distance from each point of {@code from} to the nearest point of {@code to}. */
    private static double meanDistance(List<kanji.handwriting.check.model.Point> from, List<kanji.handwriting.check.model.Point> to) {
        double sum = 0;
        for (var p : from) {
            double best = Double.MAX_VALUE;
            for (var q : to) {
                best = Math.min(best, Math.hypot(p.x() - q.x(), p.y() - q.y()));
            }
            sum += best;
        }
        return sum / from.size();
    }

    private static ReferenceShape build(String kanji, ReferenceKanji ref, String variant, CheckerConfig config) {
        List<ReferenceComponent> level = ref.components();
        for (int d = 1; d < config.componentDepth(); d++) {
            List<ReferenceComponent> next = new ArrayList<>();
            for (ReferenceComponent c : level) {
                next.addAll(c.children().isEmpty() ? List.of(c) : c.children());
            }
            level = next;
        }
        List<ReferenceShape.Component> components = new ArrayList<>();
        for (ReferenceComponent c : level) {
            List<ReferenceShape.Stroke> strokes = new ArrayList<>();
            for (ReferenceStroke s : c.strokes()) {
                strokes.add(new ReferenceShape.Stroke(s.index(), s.kvgType(), s.polyline(),
                        List.copyOf(Polylines.resample(s.polyline(), config.sampleStep()))));
            }
            components.add(new ReferenceShape.Component(label(c), c.position(), List.copyOf(strokes), parts(c, c)));
        }
        return new ReferenceShape(kanji, ref.kanji(), variant, List.copyOf(components));
    }

    /** The sub-components of {@code node}, as stroke positions within {@code owner}'s strokes. */
    private static List<ReferenceShape.Part> parts(ReferenceComponent owner, ReferenceComponent node) {
        List<ReferenceShape.Part> out = new ArrayList<>();
        for (ReferenceComponent child : node.children()) {
            List<Integer> positions = child.strokes().stream().map(owner.strokes()::indexOf).toList();
            out.add(new ReferenceShape.Part(label(child), positions, parts(owner, child)));
        }
        return List.copyOf(out);
    }

    private static String label(ReferenceComponent c) {
        if (c.element() != null) {
            return c.part() == null ? c.element() : c.element() + "#" + c.part();
        }
        int first = c.strokes().get(0).index() + 1, last = c.strokes().get(c.strokes().size() - 1).index() + 1;
        return first == last ? "stroke " + first : "strokes " + first + "-" + last;
    }
}
