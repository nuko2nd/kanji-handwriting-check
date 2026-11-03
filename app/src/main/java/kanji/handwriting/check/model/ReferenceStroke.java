package kanji.handwriting.check.model;

import java.util.List;

/**
 * One stroke of a {@link ReferenceKanji}: a single pen-down to pen-up movement.
 *
 * @param index 0-based position in stroke order (KanjiVG's stroke {@code -s1} is index 0).
 * @param polyline the stroke's centre line in reference space (109 × 109, origin top-left, y down),
 *     ordered in the <b>writing direction</b>: the first point is where the pen lands, the last
 *     is where it lifts. It has at least 2 points and is unmodifiable. Points are <em>not</em>
 *     evenly spaced: Bézier curves are sampled at equal steps of their parameter, 8 points per
 *     curve segment. Resample if you need uniform spacing.
 * @param kvgType KanjiVG's stroke shape class: one or more characters from Unicode's CJK Strokes
 *     block, e.g. {@code "㇐"} (horizontal), {@code "㇑a"} (vertical whose end touches the middle of another stroke), or
 *     {@code "㇔/㇏"} (either a dot or a right-falling sweep). {@code null} for strokes of kana,
 *     Latin letters and punctuation, which KanjiVG does not classify. See
 *     {@link kanji.handwriting.check.service.ReferenceRepository} section 4 for the full table.
 */
public record ReferenceStroke(int index, List<Point> polyline, String kvgType) {}
