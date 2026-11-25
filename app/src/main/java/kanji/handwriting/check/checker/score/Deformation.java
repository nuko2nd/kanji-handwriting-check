package kanji.handwriting.check.checker.score;

/**
 * How far the reference had to move to fit the drawing, and the resulting score. Directions are
 * from the reference to the drawing: {@code dx > 0} means the drawing is further right, and
 * {@code scale < 1} means it is smaller (for a stroke, shorter).
 *
 * @param dx shift along x, in reference units
 * @param dy shift along y (positive = lower)
 * @param rotationDegrees rotation (positive = clockwise on screen)
 * @param scale size factor
 * @param score {@code exp(-½·Σ(e/σ)²)}, where {@code e} is how far the shift length, rotation and
 *     log scale each go beyond their free allowance (0 within it), 0–1; 1 when every movement
 *     is within its allowance
 */
public record Deformation(double dx, double dy, double rotationDegrees, double scale, double score) {
    /** No movement, score 1. */
    public static final Deformation NONE = new Deformation(0, 0, 0, 1, 1);

    /**
     * Free allowance and penalty scale for one kind of movement (shift, rotation or size).
     *
     * @param free movement up to this costs nothing: units for shift, degrees for rotation, a
     *     fraction for size (0.15 = 15% larger or smaller)
     * @param sigma beyond the allowance, this much more costs about 40% of the score (same units;
     *     for size, in log scale, e.g. 0.2 ≈ a further 20%)
     */
    public record Allowance(double free, double sigma) {}

    /**
     * Builds a deformation and its score.
     *
     * @param dx shift x
     * @param dy shift y
     * @param rotationDegrees rotation
     * @param scale size factor
     * @param shift allowance for the shift length
     * @param rotation allowance for rotation
     * @param size allowance for size change
     * @return the deformation
     */
    public static Deformation of(double dx, double dy, double rotationDegrees, double scale,
                                 Allowance shift, Allowance rotation, Allowance size) {
        double a = Math.max(0, Math.hypot(dx, dy) - shift.free()) / shift.sigma();
        double b = Math.max(0, Math.abs(rotationDegrees) - rotation.free()) / rotation.sigma();
        double c = Math.max(0, Math.abs(Math.log(scale)) - Math.log(1 + size.free())) / size.sigma();
        return new Deformation(dx, dy, rotationDegrees, scale, Math.exp(-0.5 * (a * a + b * b + c * c)));
    }

    /**
     * Scores a movement whose shift is judged separately along an axis and across it.
     *
     * @param dx horizontal shift
     * @param dy vertical shift
     * @param axis 0 if {@code along} applies to x (and {@code across} to y), 1 for the reverse
     * @param rotationDegrees rotation
     * @param scale size change
     * @param along allowance for the shift along the axis
     * @param across allowance for the shift across it
     * @param rotation allowance for the rotation
     * @param size allowance for the size change
     * @return the deformation and its score
     */
    public static Deformation of(double dx, double dy, int axis, double rotationDegrees, double scale, Allowance along,
                                 Allowance across, Allowance rotation, Allowance size) {
        double a1 = Math.max(0, Math.abs(axis == 0 ? dx : dy) - along.free()) / along.sigma();
        double a2 = Math.max(0, Math.abs(axis == 0 ? dy : dx) - across.free()) / across.sigma();
        double b = Math.max(0, Math.abs(rotationDegrees) - rotation.free()) / rotation.sigma();
        double c = Math.max(0, Math.abs(Math.log(scale)) - Math.log(1 + size.free())) / size.sigma();
        return new Deformation(dx, dy, rotationDegrees, scale, Math.exp(-0.5 * (a1 * a1 + a2 * a2 + b * b + c * c)));
    }

    /**
     * Length of the shift.
     *
     * @return {@code hypot(dx, dy)}
     */
    public double shift() {
        return Math.hypot(dx, dy);
    }
}
