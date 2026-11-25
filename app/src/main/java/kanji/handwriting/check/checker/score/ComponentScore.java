package kanji.handwriting.check.checker.score;

import java.util.List;

/**
 * How well one component was drawn.
 *
 * @param label the component's label (see {@code ReferenceShape.Component})
 * @param position the component's position, or null
 * @param coverage the lowest stroke score among its strokes: the minimum, not the mean, so one
 *     missing or badly drawn detail such as the dot of 犬 is not averaged away
 * @param precision of the drawing points assigned to this component, the mean credit of their
 *     distance to its strokes (extra ink); 1 when no points were assigned
 * @param f1 harmonic mean of {@code coverage} and {@code precision}
 * @param score the component's final score: {@code f1 × placement.score}, minus the penalty for
 *     extra marks attributed to the component, at least 0
 * @param strokes score of each stroke, in stroke order
 * @param assignedPoints drawing skeleton points whose nearest reference stroke is in this component
 * @param strayPoints of those, the points farther than the stray distance from every stroke
 * @param extraMarks stray ink patches attributed to this component
 * @param placement how far the whole component had to move to fit the drawing, relative to the
 *     rest of the character ({@link Deformation#NONE} with distance scoring)
 * @param parts how far each part inside the component (and each part inside those) had to move,
 *     relative to its parent; empty with distance scoring or for an undivided component
 */
public record ComponentScore(
        String label,
        String position,
        double coverage,
        double precision,
        double f1,
        double score,
        List<StrokeScore> strokes,
        int assignedPoints,
        int strayPoints,
        int extraMarks,
        Deformation placement,
        java.util.List<PartScore> parts) {

    /**
     * One reference stroke.
     *
     * @param strokeIndex 0-based stroke index in the whole character
     * @param kvgType KanjiVG stroke type, or null
     * @param coverage mean credit of the stroke's sample points by distance to the drawing's ink
     *     (with snapping, measured after the stroke was snapped onto the ink)
     * @param deformation how far the stroke had to move, turn and stretch within its component
     *     ({@link Deformation#NONE} with distance scoring)
     * @param score {@code coverage × deformation.score}
     */
    public record StrokeScore(int strokeIndex, String kvgType, double coverage, Deformation deformation, double score) {}
}
