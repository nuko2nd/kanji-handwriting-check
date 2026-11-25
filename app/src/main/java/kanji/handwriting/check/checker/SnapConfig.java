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
 * @param followRunOn ink that carries a stroke on past its end (followed along the drawn line,
 *     never past another stroke) counts as that stroke drawn longer, not as an extra mark
 * @param componentMaxAspectChange after a component of two or more strokes is placed (same scale
 *     both ways), it may also be stretched separately in each direction, each axis within
 *     {@code [1/(1+c), 1+c]} (曜's 日 drawn tall and narrow); scored with {@code componentSize}. 0 turns
 *     this off
 * @param runOnReach with {@code followRunOn}, how far (reference units) past a stroke's end ink is
 *     followed as that stroke drawn longer: enough for a flick or hook at the end; ink beyond it
 *     is extra. 0 or less: 80% of the stroke's length, at least 30 units (length not judged)
 * @param componentCrossShift free shift and penalty scale for a component placed beside or above
 *     another (KanjiVG position left/right/top/bottom), across that arrangement: a left radical
 *     sitting higher or lower than the model (晴's 日) varies far more in handwriting than the gap
 *     between the two sides does, which {@code componentShift} judges
 * @param runOnMaxTurnDegrees with {@code followRunOn}, run-on ink must stay within this angle of
 *     the stroke's direction out of its end; ink that turns more is another stroke joined on (彳's
 *     second stroke on 亻), not the stroke drawn longer. 180 follows connected ink anywhere
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
        int iterations,
        boolean followRunOn,
        double componentMaxAspectChange,
        double runOnMaxTurnDegrees,
        double runOnReach,
        Allowance componentCrossShift) {

    /**
     * Default snapping settings.
     *
     * @return components may move up to 14 units / 15° / ±40%, free up to 4 units (8 across a
     *     left-right or top-bottom arrangement) / 6° / 20%,
     *     and be stretched up to 30% in either direction; parts within them, stretched separately
     *     in each direction, free up to 6 units / 60% (each axis from half to double size); strokes
     *     up to 10 units / 25° / ±60% within their part, free up to 3 units / 8° / 45% (side
     *     radicals are drawn smaller and placed differently in real handwriting, and short sweeps
     *     shorter: tuned on handwritten sheets); short strokes (under 20 units) only shift, free up
     *     to 6 units, and must point within 25° of the reference; ink running on from a stroke's end
     *     counts as that stroke drawn longer (length is judged by the checker's length rules)
     */
    public static SnapConfig defaults() {
        return new SnapConfig(
                14, 15, 0.4, new Allowance(4, 4), new Allowance(6, 10), new Allowance(0.2, 0.12),
                new Allowance(6, 5), new Allowance(8, 10), new Allowance(0.6, 0.3), 1.0, 8,
                10, 25, 0.6, new Allowance(3, 4), new Allowance(8, 12), new Allowance(0.45, 0.25),
                20, new Allowance(6, 5), new Allowance(25, 15),
                12, true, 0.3, 180, 0, new Allowance(8, 4));
    }

    /**
     * These settings with no penalty for where components (radicals) sit relative to each other,
     * or for their size: each may move and resize freely up to its limit. Parts inside a
     * component, and components of a single stroke, are still judged. Used by
     * {@link CheckerConfig.Strictness#RELAXED}.
     *
     * @return a copy with free component placement
     */
    public SnapConfig ignoringPlacement() {
        return new SnapConfig(componentMaxShift, componentMaxRotationDegrees, componentMaxScaleChange,
                new Allowance(componentMaxShift, componentShift.sigma()),
                new Allowance(componentMaxRotationDegrees, componentRotation.sigma()),
                new Allowance(componentMaxScaleChange, componentSize.sigma()),
                partShift, partRotation, partSize, partMaxScaleChange, partMaxShift,
                strokeMaxShift, strokeMaxRotationDegrees, strokeMaxScaleChange, strokeShift, strokeRotation, strokeSize,
                minShapeLength, shortStrokeShift, shortStrokeRotation, iterations, followRunOn,
                componentMaxAspectChange, runOnMaxTurnDegrees, runOnReach,
                new Allowance(componentMaxShift, componentCrossShift.sigma()));
    }

    /**
     * These settings with stroke length judged loosely: a stroke may be drawn up to
     * {@code free} longer or shorter than the model at no cost, and ink carrying a stroke on past
     * its end is that stroke drawn longer ({@code followRunOn}). Used by
     * {@link CheckerConfig.Strictness#RELAXED}, so that 土 and 士 are accepted for each other.
     *
     * @param free length change that costs nothing, as a fraction
     * @return a copy with loose stroke length
     */
    public SnapConfig ignoringLength(double free) {
        return new SnapConfig(componentMaxShift, componentMaxRotationDegrees, componentMaxScaleChange, componentShift,
                componentRotation, componentSize, partShift, partRotation, partSize, partMaxScaleChange, partMaxShift,
                strokeMaxShift, strokeMaxRotationDegrees, strokeMaxScaleChange, strokeShift, strokeRotation,
                new Allowance(Math.min(free, strokeMaxScaleChange), strokeSize.sigma()),
                minShapeLength, shortStrokeShift, shortStrokeRotation, iterations, true, componentMaxAspectChange, 180, 0,
                componentCrossShift);
    }

    /**
     * These settings with other component placement allowances.
     *
     * @param shift free shift and penalty scale for a component
     * @param rotation free rotation and penalty scale for a component
     * @param size free size change and penalty scale for a component
     * @return a copy with those allowances
     */
    public SnapConfig withComponentAllowances(Allowance shift, Allowance rotation, Allowance size) {
        return new SnapConfig(componentMaxShift, componentMaxRotationDegrees, componentMaxScaleChange, shift, rotation, size,
                partShift, partRotation, partSize, partMaxScaleChange, partMaxShift, strokeMaxShift,
                strokeMaxRotationDegrees, strokeMaxScaleChange, strokeShift, strokeRotation, strokeSize, minShapeLength,
                shortStrokeShift, shortStrokeRotation, iterations, followRunOn, componentMaxAspectChange,
                runOnMaxTurnDegrees, runOnReach, componentCrossShift);
    }
}
