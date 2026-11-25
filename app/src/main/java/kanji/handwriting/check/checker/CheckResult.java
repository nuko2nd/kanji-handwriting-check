package kanji.handwriting.check.checker;

import java.awt.image.BufferedImage;
import java.util.List;
import kanji.handwriting.check.checker.align.Alignment;
import kanji.handwriting.check.checker.score.ComponentScore;
import kanji.handwriting.check.checker.score.StrayMark;

/**
 * Outcome of checking one drawing.
 *
 * @param kanji the target character
 * @param referenceKanji the character whose KanjiVG data was used (an override may differ)
 * @param referenceVariant the variant form the drawing matched best (e.g. {@code "Straight"}), or
 *     null for KanjiVG's standard form
 * @param verdict pass, unsure (check manually) or fail; see {@link KanjiChecker}
 * @param score the lowest component score, minus the clamp penalty if an alignment limit was hit
 * @param components per-component scores, worst first
 * @param strayMarks extra-ink patches
 * @param strayPoints skeleton points farther than the stray distance from every stroke
 * @param alignment the transform applied and which limits were hit
 * @param feedback human-readable messages (alignment problems first, then failing components)
 * @param overlay the feedback image
 */
public record CheckResult(
        String kanji,
        String referenceKanji,
        String referenceVariant,
        Verdict verdict,
        double score,
        List<ComponentScore> components,
        List<StrayMark> strayMarks,
        int strayPoints,
        Alignment alignment,
        List<String> feedback,
        BufferedImage overlay) {

    /**
     * Whether the drawing passed.
     *
     * @return {@code verdict() == Verdict.PASS}
     */
    public boolean pass() {
        return verdict == Verdict.PASS;
    }

    /**
     * The lowest-scoring component.
     *
     * @return {@code components().get(0)}
     */
    public ComponentScore worst() {
        return components.get(0);
    }
}
