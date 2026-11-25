package kanji.handwriting.check.checker.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.model.Point;

/**
 * Radicals that learners mix up (亻 and 彳, 日 and 目, 木 and 禾). A drawing that uses the wrong
 * one is not a random extra line: it is a whole, familiar shape. So, besides checking a drawing
 * against the kanji, the checker can check it against the same kanji with one radical swapped for
 * a look-alike; if the look-alike fits clearly better, the wrong radical was written.
 */
public final class ConfusableRadicals {
    /** Groups of look-alike radicals; each may be mistaken for any other in its group. */
    static final List<String> GROUPS = List.of("亻彳", "氵冫", "扌木牛", "日目月", "木禾米", "土士", "礻衤", "冖宀");

    private final ReferenceShapeLoader loader;

    /**
     * Creates the swapper.
     *
     * @param loader source of the look-alike radicals' shapes
     */
    public ConfusableRadicals(ReferenceShapeLoader loader) {
        this.loader = loader;
    }

    /**
     * A model with one component replaced by a look-alike radical.
     *
     * @param shape the model the drawing was checked against
     * @param component index of the replaced component
     * @param radical the look-alike radical
     * @param model the reshaped model
     */
    public record Swap(ReferenceShape shape, int component, String radical, ReferenceShape model) {}

    /**
     * Every model obtained by replacing one component of {@code shape} with a look-alike radical,
     * fitted into the replaced component's bounding box.
     *
     * @param shape the model
     * @param config settings for loading the radicals
     * @return the swapped models (empty if no component has look-alikes)
     */
    public List<Swap> swaps(ReferenceShape shape, CheckerConfig config) {
        List<Swap> out = new ArrayList<>();
        int maxIndex = shape.components().stream().flatMap(c -> c.strokes().stream())
                .mapToInt(ReferenceShape.Stroke::index).max().orElse(0);
        for (int c = 0; c < shape.components().size(); c++) {
            ReferenceShape.Component comp = shape.components().get(c);
            String label = baseLabel(comp.label());
            for (String other : lookAlikes(label)) {
                Optional<ReferenceShape> radical = loader.load(other, config);
                if (radical.isEmpty()) {
                    continue;
                }
                ReferenceShape.Component swapped = fitInto(radical.get(), comp, other, maxIndex + 1);
                if (swapped == null) {
                    continue;
                }
                List<ReferenceShape.Component> comps = new ArrayList<>(shape.components());
                comps.set(c, swapped);
                out.add(new Swap(shape, c, other,
                        new ReferenceShape(shape.kanji(), shape.sourceKanji(), shape.variant(), List.copyOf(comps))));
            }
        }
        return out;
    }

    /** The look-alikes of a radical (empty if it has none). */
    static List<String> lookAlikes(String radical) {
        List<String> out = new ArrayList<>();
        if (radical == null || radical.codePointCount(0, radical.length()) != 1) {
            return out;
        }
        for (String group : GROUPS) {
            if (group.contains(radical)) {
                group.codePoints().mapToObj(Character::toString).filter(r -> !r.equals(radical) && !out.contains(r))
                        .forEach(out::add);
            }
        }
        return out;
    }

    /**
     * A KanjiVG label without its "#n" suffix (行#2 → 行).
     *
     * @param label a component label, or null
     * @return the label without the suffix
     */
    public static String baseLabel(String label) {
        if (label == null) {
            return null;
        }
        int hash = label.indexOf('#');
        return hash < 0 ? label : label.substring(0, hash);
    }

    /**
     * All of {@code radical}'s strokes, scaled separately in each direction so that their bounding
     * box matches {@code comp}'s, as one component numbered from {@code firstIndex}.
     */
    private static ReferenceShape.Component fitInto(ReferenceShape radical, ReferenceShape.Component comp, String label,
                                                    int firstIndex) {
        double[] to = bounds(comp.strokes()), from = bounds(allStrokes(radical));
        if (to == null || from == null) {
            return null;
        }
        double sx = (to[2] - to[0]) / Math.max(1, from[2] - from[0]), sy = (to[3] - to[1]) / Math.max(1, from[3] - from[1]);
        List<ReferenceShape.Stroke> strokes = new ArrayList<>();
        int index = firstIndex;
        for (ReferenceShape.Stroke s : allStrokes(radical)) {
            strokes.add(new ReferenceShape.Stroke(index++, s.kvgType(), map(s.path(), from, to, sx, sy),
                    map(s.samples(), from, to, sx, sy)));
        }
        return new ReferenceShape.Component(label, comp.position(), List.copyOf(strokes), List.of());
    }

    private static List<ReferenceShape.Stroke> allStrokes(ReferenceShape shape) {
        List<ReferenceShape.Stroke> out = new ArrayList<>();
        shape.components().forEach(c -> out.addAll(c.strokes()));
        return out;
    }

    private static List<Point> map(List<Point> pts, double[] from, double[] to, double sx, double sy) {
        List<Point> out = new ArrayList<>(pts.size());
        for (Point p : pts) {
            out.add(new Point(to[0] + (p.x() - from[0]) * sx, to[1] + (p.y() - from[1]) * sy));
        }
        return out;
    }

    /** {minX, minY, maxX, maxY} of the strokes' samples, or null if there are none. */
    private static double[] bounds(List<ReferenceShape.Stroke> strokes) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        boolean any = false;
        for (ReferenceShape.Stroke s : strokes) {
            for (Point p : s.samples()) {
                x0 = Math.min(x0, p.x());
                y0 = Math.min(y0, p.y());
                x1 = Math.max(x1, p.x());
                y1 = Math.max(y1, p.y());
                any = true;
            }
        }
        return any ? new double[] {x0, y0, x1, y1} : null;
    }
}
