package kanji.handwriting.check.model;

/**
 * A 2D point. The owning object states which coordinate space it is in:
 * <ul>
 *   <li><b>Reference space</b>: KanjiVG's 109 × 109 square, used by {@link ReferenceStroke}.
 *   <li><b>Image space</b>: pixel coordinates of a cell image, used by {@link StrokeResult} and
 *       unexplained ink.
 * </ul>
 * Both spaces put the origin at the top-left, with x increasing to the right and y increasing
 * <b>downward</b>.
 */
public record Point(double x, double y) {}
