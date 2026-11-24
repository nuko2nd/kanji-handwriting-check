package kanji.handwriting.check.model;

import java.util.List;

/**
 * One component of a character (a radical or another building block such as 口 or 夫), with the
 * strokes that write it and where it sits in the character. Built from KanjiVG's nested
 * {@code <g>} groups by {@link kanji.handwriting.check.service.ReferenceRepository}; section 7 of
 * that class's documentation gives the full rules.
 *
 * <p>Components form a tree: 漢 has the top-level components 氵 (left) and an unnamed right
 * part. The right part contains 艹 (top) and an unnamed bottom part, and that contains 口 and 夫.
 *
 * @param element the component's character, e.g. {@code "氵"}, as KanjiVG names it; {@code null}
 *     for an unnamed grouping, either one KanjiVG leaves unnamed (the left and right halves of 門)
 *     or one added to hold loose strokes (see {@code children}). The character is the form
 *     actually written, so {@code "氵"} rather than {@code "水"}.
 * @param original when {@link #variant} is true, the standard form this component is a variant
 *     of, e.g. {@code "水"} for 氵; otherwise usually {@code null}
 * @param variant true if {@code element} is a variant form (e.g. 氵 for 水, 亻 for 人)
 * @param partial true if KanjiVG marks the component as only partly present (some of its usual
 *     strokes are left out or shared)
 * @param part {@code null} for a whole component. Otherwise, this is one piece of a component that
 *     KanjiVG splits around another, numbered from 1 in stroke order. This happens in two cases:
 *     <ul>
 *       <li>The pieces sit side by side or stacked: 行 in 街 is piece 1 (彳, strokes 1–3,
 *           {@code left}) and piece 2 (亍, strokes 10–12, {@code right}), with 圭 between them as a
 *           sibling.
 *       <li>The pieces interlock at different depths: in 乗, 千 is piece 1 (strokes 1–2) at the
 *           top level, and piece 2 (stroke 7) is nested inside 木.
 *     </ul>
 *     Pieces that enclose what lies between them, such as 囗 around 或 in 國, are merged into one
 *     whole component instead. See section 7 of the repository documentation.
 * @param position where the component sits relative to its siblings, as KanjiVG's raw
 *     {@code kvg:position} value: {@code left}, {@code right}, {@code top}, {@code bottom},
 *     {@code middle}, {@code kamae} (enclosing, as 門 or 囗), {@code tare} (hanging over the
 *     top-left, as 广), {@code nyo} (wrapping the bottom-left, as 辶), or {@code null} when not
 *     given. See the repository documentation for the less common values. For a side-by-side or
 *     stacked piece (see {@code part}), it is instead the piece's place among its pieces:
 *     {@code left}/{@code middle}/{@code right} or {@code top}/{@code middle}/{@code bottom}.
 * @param radical which radical classification counts this component as the character's radical:
 *     {@code general}, {@code tradit} (traditional), {@code nelson} (Nelson dictionary) or
 *     {@code jis}; {@code null} if it is not the radical. A character can have different radicals
 *     under different classifications, so more than one component may be non-null.
 * @param phon the phonetic element this component provides, if KanjiVG records one; otherwise
 *     {@code null}
 * @param strokes every stroke of this component, including those of its sub-components, in stroke
 *     order. These are the same {@link ReferenceStroke} objects as in
 *     {@link ReferenceKanji#strokes()}, so {@code index()} is still the position in the whole
 *     character. An enclosing component that KanjiVG splits around another (囗 in 國 is strokes 1,
 *     2 and 11) appears once, and its strokes need not be consecutive.
 * @param bounds the smallest rectangle containing all of this component's stroke centre lines, in
 *     reference space (109 × 109, origin top-left, y down)
 * @param children sub-components, ordered by their first stroke; empty for a component KanjiVG
 *     does not divide further. When non-empty, the children cover every stroke of this component
 *     exactly once. Strokes that KanjiVG places directly in a component next to its sub-components
 *     are wrapped in unnamed components, so 夫 in 漢 has the children [unnamed: ㇐] and [大].
 */
public record ReferenceComponent(
        String element,
        String original,
        boolean variant,
        boolean partial,
        Integer part,
        String position,
        String radical,
        String phon,
        List<ReferenceStroke> strokes,
        Bounds bounds,
        List<ReferenceComponent> children) {}
