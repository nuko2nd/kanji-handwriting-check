package kanji.handwriting.check.checker;

import kanji.handwriting.check.checker.score.Deformation.Allowance;

/**
 * Settings for snap scoring ({@link CheckerConfig.Scoring#SNAP}): how far each component, each of
 * its parts, and then each stroke may move to fit the ink, how much movement is free, and how
 * quickly the score drops beyond that. Distances are in reference units (the cell is 109 × 109).
 *
 * <p>Handwriting never sits exactly on the model, so each kind of movement has a free allowance.
 * Beyond it, the penalty is {@code exp(-½·Σ(excess/σ)²)}: one σ past the allowance in one respect
 * costs about 40% of the score, half a σ about 12%.
 *
 * @param componentMaxShift farthest a component (or a part within it) may move
 * @param componentMaxRotationDegrees largest component or part rotation either way
 * @param componentMaxScaleChange component or part size stays within {@code [1/(1+c), 1+c]}
 * @param componentShift free shift and penalty scale for a component's placement in the character
 * @param componentRotation free rotation and penalty scale for a component, in degrees
 * @param componentSize free size change and penalty scale for a component
 * @param partShift free shift and penalty scale for a part (a sub-component, e.g. 口 in 吾)
 *     relative to its component. Lenient: proportions inside a component vary a lot in real
 *     handwriting.
 * @param partRotation free rotation and penalty scale for a part
 * @param partSize free size change and penalty scale for a part, applied to whichever axis
 *     changed more (parts are fitted with separate horizontal and vertical scales)
 * @param partMaxScaleChange a part's scale on each axis stays within {@code [1/(1+c), 1+c]}
 * @param partMaxShift farthest a part may move relative to its component during fitting. Kept
 *     smaller than {@code componentMaxShift}: a small part searching far can land on a
 *     neighbour's ink
 * @param strokeMaxShift farthest a stroke may move within its snapped part
 * @param strokeMaxRotationDegrees largest stroke rotation either way
 * @param strokeMaxScaleChange stroke size (length) stays within {@code [1/(1+c), 1+c]}
 * @param strokeShift free shift and penalty scale for a stroke within its part
 * @param strokeRotation free rotation and penalty scale for a stroke, in degrees
 * @param strokeSize free length change and penalty scale for a stroke
 * @param minShapeLength strokes shorter than this (dots, ticks) are fitted by shifting only; their
 *     direction is instead read from the ink they land on and checked against
 *     {@code shortStrokeRotation}
 * @param shortStrokeShift free shift and penalty scale for a short stroke (dots wander more than
 *     long strokes)
 * @param shortStrokeRotation free angle and penalty scale between a short stroke and the ink it
 *     lands on, in degrees. Catches a dot written the wrong way (e.g. 言's first stroke as ／
 *     instead of ＼) while tolerating ordinary variation in dots.
 * @param iterations fitting iterations per component, part and stroke
 */
public record SnapConfig(
        double componentMaxShift,
        double componentMaxRotationDegrees,
        double componentMaxScaleChange,
        Allowance componentShift,
        Allowance componentRotation,
        Allowance componentSize,
        Allowance partShift,
        Allowance partRotation,
        Allowance partSize,
        double partMaxScaleChange,
        double partMaxShift,
        double strokeMaxShift,
        double strokeMaxRotationDegrees,
        double strokeMaxScaleChange,
        Allowance strokeShift,
        Allowance strokeRotation,
        Allowance strokeSize,
        double minShapeLength,
        Allowance shortStrokeShift,
        Allowance shortStrokeRotation,
        int iterations) {

    /**
     * Default snapping settings.
     *
     * @return components may move up to 14 units / 15° / ±40%, free up to 3 units / 6° / 10%;
     *     parts within them, stretched separately in each direction, free up to 6 units / 60% (each
     *     axis from half to double size); strokes up to 10 units / 25° / ±60%
     *     within their part, free up to 3 units / 8° / 10%; short strokes (under 20 units) only
     *     shift, free up to 6 units, and must point within 25° of the reference
     */
    public static SnapConfig defaults() {
        return new SnapConfig(
                14, 15, 0.4, new Allowance(4, 4), new Allowance(6, 10), new Allowance(0.1, 0.12),
                new Allowance(6, 5), new Allowance(8, 10), new Allowance(0.6, 0.3), 1.0, 8,
                10, 25, 0.6, new Allowance(3, 4), new Allowance(8, 12), new Allowance(0.2, 0.15),
                20, new Allowance(6, 5), new Allowance(25, 15),
                12);
    }
}
