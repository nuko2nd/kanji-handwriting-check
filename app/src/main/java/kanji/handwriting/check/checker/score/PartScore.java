package kanji.handwriting.check.checker.score;

/**
 * How far a part of a component (a KanjiVG sub-component, e.g. 口 in 吾) had to move, turn and
 * resize, relative to its component, to fit the drawing. Its score multiplies the scores of all
 * strokes in the part.
 *
 * @param label the part's label, e.g. {@code "口"} or {@code "strokes 9-10"}
 * @param placement the movement and its score (lenient: see {@code SnapConfig.partShift})
 */
public record PartScore(String label, Deformation placement) {}
