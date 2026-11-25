package kanji.handwriting.check.checker.reference;

import java.util.List;
import kanji.handwriting.check.model.Point;

/**
 * The reference a drawing is scored against: the character's components at the configured depth,
 * each with its strokes sampled into evenly spaced points in reference space (109 × 109).
 *
 * @param kanji the character being checked
 * @param sourceKanji the character whose KanjiVG data was used (differs from {@code kanji} only
 *     when an override applies)
 * @param variant the name of the bundled variant form used (e.g. {@code "Straight"} for お), or
 *     null for KanjiVG's standard form; see {@link kanji.handwriting.check.service.ReferenceRepository#variants}
 * @param components the scored components, in stroke order of their first stroke; together they
 *     contain every stroke exactly once
 */
public record ReferenceShape(String kanji, String sourceKanji, String variant, List<Component> components) {

    /**
     * One scored component.
     *
     * @param label human-readable name: the element (e.g. {@code "言"}), with the piece number for a
     *     split piece (e.g. {@code "行#1"}), or the stroke numbers for an unnamed group
     *     (e.g. {@code "strokes 2-3"})
     * @param position KanjiVG position or derived split position, or null
     * @param strokes the component's strokes in stroke order
     * @param parts the component's structure below it (KanjiVG's sub-components, e.g. 五 and 口
     *     in 吾), as groups of positions in {@code strokes}; empty if KanjiVG does not divide it.
     *     Snap scoring fits these as units before fitting single strokes, so that a part drawn in
     *     different proportions from KanjiVG's is matched as a whole.
     */
    public record Component(String label, String position, List<Stroke> strokes, List<Part> parts) {}

    /**
     * A sub-component inside a scored component.
     *
     * @param label the part's label, in the same style as component labels
     * @param strokes positions (0-based, in the owning component's {@code strokes}) of its strokes
     * @param parts its own sub-parts; empty for a part KanjiVG does not divide further
     */
    public record Part(String label, List<Integer> strokes, List<Part> parts) {}

    /**
     * One reference stroke.
     *
     * @param index 0-based stroke index in the whole character
     * @param kvgType KanjiVG stroke type, or null
     * @param path the stroke's centre line as flattened from KanjiVG
     * @param samples points along {@code path} at the configured spacing
     */
    public record Stroke(int index, String kvgType, List<Point> path, List<Point> samples) {}

    /**
     * This shape with every stroke point moved by a mapping (component labels and parts kept).
     *
     * @param map the point mapping
     * @return the mapped shape
     */
    public ReferenceShape map(kanji.handwriting.check.checker.align.PointMap map) {
        List<Component> mapped = new java.util.ArrayList<>();
        for (Component c : components) {
            List<Stroke> strokes = c.strokes().stream()
                    .map(st -> new Stroke(st.index(), st.kvgType(), map.apply(st.path()), map.apply(st.samples())))
                    .toList();
            mapped.add(new Component(c.label(), c.position(), strokes, c.parts()));
        }
        return new ReferenceShape(kanji, sourceKanji, variant, List.copyOf(mapped));
    }
}
