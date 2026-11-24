package kanji.handwriting.check.checker.align;

/**
 * Result of aligning the drawing to the reference.
 *
 * @param transform the applied transform, mapping drawing points into reference space; within
 *     the configured limits, except that a drawing far too small, too large or too far off centre
 *     is first normalised by its outline (see {@link Aligner})
 * @param wanted the transform the fit asked for before clamping; differs from {@code transform}
 *     only where a limit was hit
 * @param scaleClamped the fit wanted a scale outside the limits
 * @param rotationClamped the fit wanted a rotation outside the limits
 * @param translationClamped the fit wanted a shift outside the limits on either axis
 * @param iterations iterations run
 */
public record Alignment(
        SimilarityTransform transform,
        SimilarityTransform wanted,
        boolean scaleClamped,
        boolean rotationClamped,
        boolean translationClamped,
        int iterations) {

    /**
     * Whether any limit was hit.
     *
     * @return true if scale, rotation or translation was clamped
     */
    public boolean anyClamped() {
        return scaleClamped || rotationClamped || translationClamped;
    }
}
