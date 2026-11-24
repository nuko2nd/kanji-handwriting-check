package kanji.handwriting.check.checker.score;

import java.util.List;
import kanji.handwriting.check.checker.reference.ReferenceShape;
import kanji.handwriting.check.model.Point;

/**
 * Everything a scorer computed. The first three fields are the result; the rest are per-point
 * details used to draw the feedback overlay.
 *
 * @param components one score per reference component, in the reference's component order
 * @param strayMarks extra-ink patches at least {@code strayMinArea} in size
 * @param strayPoints drawing skeleton points farther than the stray distance from every stroke
 * @param scoredShape the reference the drawing was finally compared with: the original reference
 *     for distance scoring, or the reference snapped onto the ink for snap scoring. The per-point
 *     arrays below refer to this shape.
 * @param alignedSkeleton the drawing's skeleton points after alignment, in reference space
 * @param skeletonComponent for each aligned skeleton point, the index of its assigned component
 * @param skeletonStray for each aligned skeleton point, whether it is stray
 * @param strokeCovered for each component, for each of its strokes, for each sample point of
 *     {@code scoredShape}, whether drawing ink earns at least half credit there
 * @param strayInk ink pixels (in reference space) belonging to the reported stray marks
 */
public record ScoreReport(
        List<ComponentScore> components,
        List<StrayMark> strayMarks,
        int strayPoints,
        ReferenceShape scoredShape,
        List<Point> alignedSkeleton,
        int[] skeletonComponent,
        boolean[] skeletonStray,
        boolean[][][] strokeCovered,
        List<Point> strayInk) {}
