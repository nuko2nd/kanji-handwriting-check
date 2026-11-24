package kanji.handwriting.check.checker;

import java.util.Map;

/**
 * Every tunable of the checker in one place. Distances are in KanjiVG reference units (the
 * character cell is 109 × 109). Start from {@link #defaults()} and change fields with
 * {@link #toBuilder()}, e.g. {@code CheckerConfig.defaults().toBuilder().tolerance(4).build()}.
 *
 * @param tolerance τ: a reference point counts as fully covered, and a user point as fully on
 *     target, when the other is within this distance
 * @param creditFalloff beyond τ, credit falls linearly to 0 at {@code creditFalloff × tolerance}
 *     (see {@link #creditZeroDistance()}). 1 means a hard cutoff at τ; larger values let ink that
 *     is slightly off still earn partial credit, which suits real handwriting.
 * @param strayDistance user ink farther than this from every reference stroke is "stray" (extra
 *     ink). Independent of τ, so loosening coverage for messy handwriting does not also hide
 *     extra strokes and over-long strokes.
 * @param sampleStep spacing of the points sampled along each reference stroke
 * @param componentDepth which level of the component tree is scored: 1 = top-level components
 *     (言 and 吾 in 語), 2 = their children where they have any, and so on
 * @param scaleMin smallest scale the alignment may apply. Overall size is not scored, so this is
 *     wide: it only stops the fit from blowing up a drawing that is not the character at all.
 * @param scaleMax largest scale the alignment may apply (see {@code scaleMin})
 * @param sizeNoteTolerance when the drawing had to be scaled by more than this fraction (0.15 =
 *     15%), the feedback mentions that it was drawn small or large. Informational only: it does
 *     not affect the score or the verdict.
 * @param maxRotationDegrees largest rotation, either way, the alignment may apply
 * @param maxTranslation largest shift along each axis the alignment may apply. Like size, where the
 *     character sits in the grid is not scored, so this is generous.
 * @param offCentreNote a drawing that had to be shifted more than this is noted as off-centre
 *     (informational only); a centroid offset this large also makes the alignment try moving the
 *     drawing as a whole
 * @param icpIterations maximum number of alignment iterations
 * @param icpMaxPairDistance a user point takes part in alignment only if a reference point is
 *     within this distance, so misplaced components do not drag the whole drawing
 * @param passThreshold the kanji passes when its score is at least this (and see
 *     {@code minStrokeCoverage})
 * @param minStrokeCoverage the kanji also fails if any stroke's coverage is below this. Coverage
 *     enters the component score through F1, which dilutes it (coverage 0.75 with perfect
 *     precision still scores 0.86), so a stroke drawn 30% too short would otherwise pass.
 * @param unsureThreshold below this score the drawing fails outright; between this and
 *     {@code passThreshold} it is UNSURE (for a person to check) unless a definite error is found
 * @param missingCoverage a stroke covered less than this counts as missing, which always fails
 * @param unsureLengthChange a long stroke drawn shorter than {@code 1 / (1 + this)} of the model
 *     makes the drawing UNSURE rather than PASS, without affecting the score: subtle length
 *     errors such as 未 for 末 are flagged for a person to check
 * @param unsureLengthGrowth a long stroke drawn more than this fraction longer than the model
 *     makes the drawing UNSURE (末 for 未). Looser than the shortening limit, because the ink
 *     measured past a stroke's ends includes the pen's rounded tip and sloppy overshoot
 * @param unsureLengthDifference a stroke's length only counts as wrong if it is also off by more
 *     than this many reference units: a short stroke's measured length is noisy
 * @param unsureRotationDegrees a stroke at least {@code unsureRotationMinLength} long turned by
 *     more than this many degrees makes the drawing UNSURE (千's slanted first stroke for 干's
 *     level one). Short strokes such as dots turn much more in normal handwriting
 * @param unsureRotationMinLength model length, in reference units, from which a stroke's turn is
 *     checked against {@code unsureRotationDegrees}
 * @param clampPenalty subtracted from the kanji score when the rotation or translation limit was
 *     hit. Size is never penalised.
 * @param strayMinArea smallest patch of stray ink, in square reference units, that counts as an
 *     extra mark (smaller specks are ignored as noise)
 * @param strayPenalty subtracted from a component's score for each extra mark attributed to it
 * @param inkThreshold grey level (0–255) below which a pixel is ink
 * @param canvasSize side of the drawing canvas in pixels; 0 means use the image width
 * @param scoring how strokes are compared with the drawing (see {@link Scoring})
 * @param snap limits and penalty scales for {@link Scoring#SNAP}
 * @param language language of feedback messages, printed results and the overlay legend
 * @param referenceOverrides alternate reference per character, for kanji whose KanjiVG form
 *     differs from the taught handwritten form (e.g. 令, 心, 糸, 言): maps the target character to
 *     the character whose KanjiVG data should be used instead. Empty by default.
 */
public record CheckerConfig(
        double tolerance,
        double creditFalloff,
        double strayDistance,
        double sampleStep,
        int componentDepth,
        double scaleMin,
        double scaleMax,
        double sizeNoteTolerance,
        double maxRotationDegrees,
        double maxTranslation,
        double offCentreNote,
        int icpIterations,
        double icpMaxPairDistance,
        double passThreshold,
        double minStrokeCoverage,
        double unsureThreshold,
        double missingCoverage,
        double unsureLengthChange,
        double unsureLengthGrowth,
        double unsureLengthDifference,
        double unsureRotationDegrees,
        double unsureRotationMinLength,
        double clampPenalty,
        double strayMinArea,
        double strayPenalty,
        int inkThreshold,
        int canvasSize,
        Scoring scoring,
        SnapConfig snap,
        Language language,
        Map<String, String> referenceOverrides) {

    /** How the drawing is compared with the reference strokes. */
    public enum Scoring {
        /**
         * Snap each component, then each stroke, onto the nearest ink, and score how far they had
         * to move, turn and stretch, plus how well they fit afterwards. Lenient to the small
         * displacements of real handwriting; the score drops smoothly as they grow.
         */
        SNAP,
        /** Compare the drawing with the reference where it stands, by distance only. */
        DISTANCE
    }

    /** Output language. */
    public enum Language {
        /** English. */
        EN,
        /** Japanese. */
        JA
    }

    /** Copies the override map so the config stays immutable. */
    public CheckerConfig {
        referenceOverrides = Map.copyOf(referenceOverrides);
    }

    /**
     * The default settings.
     *
     * <p>τ and the falloff were chosen on synthetic "sloppy but correct" drawings (every stroke
     * randomly shifted, rotated and resized) against programmatic errors: τ = 6 with credit
     * reaching 0 at 9.6 accepts all mildly sloppy drawings and a third of moderately sloppy ones,
     * while still catching every dropped, extended or shifted stroke, every stray mark, and
     * 9 in 10 strokes drawn 30% short. The original brief's τ = 5 with a hard cutoff accepted
     * only 7% of the moderately sloppy drawings. Tune on real handwriting.
     *
     * @return τ = 6 (credit to 0 at 9.6), stray beyond 10 units, 0.5-unit sampling, depth 1,
     *     any size from 0.5× to 2× (noted beyond ±15%, never penalised; beyond that the outline
     *     is normalised first), ±5°, ±8 units, pass at 0.8 with every stroke at least 90% covered
     *     and no long stroke turned more than 12°
     */
    public static CheckerConfig defaults() {
        return new CheckerConfig(6.0, 1.6, 10.0, 0.5, 1, 0.5, 2.0, 0.15, 5.0, 25.0, 8.0, 30, 7.5,
                0.8, 0.9, 0.55, 0.5, 0.19, 0.26, 10.0, 12.0, 40.0, 0.2, 4.0, 0.3, 128, 0, Scoring.SNAP, SnapConfig.defaults(), Language.EN, Map.of());
    }

    /**
     * Distance at which a point stops earning any credit.
     *
     * @return {@code creditFalloff × tolerance}
     */
    public double creditZeroDistance() {
        return creditFalloff * tolerance;
    }

    /**
     * Starts a modified copy.
     *
     * @return a builder holding this config's values
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Builds a {@link CheckerConfig}; each setter names the field it changes. */
    public static final class Builder {
        private double tolerance, creditFalloff, strayDistance, sampleStep, scaleMin, scaleMax, sizeNoteTolerance, maxRotationDegrees,
                maxTranslation, offCentreNote,
                icpMaxPairDistance, passThreshold, minStrokeCoverage, unsureThreshold, missingCoverage, unsureLengthChange, unsureLengthGrowth, unsureLengthDifference, unsureRotationDegrees, unsureRotationMinLength, clampPenalty,
                strayMinArea, strayPenalty;
        private int componentDepth, icpIterations, inkThreshold, canvasSize;
        private Scoring scoring;
        private SnapConfig snap;
        private Language language;
        private Map<String, String> referenceOverrides;

        private Builder(CheckerConfig c) {
            tolerance = c.tolerance;
            creditFalloff = c.creditFalloff;
            strayDistance = c.strayDistance;
            sampleStep = c.sampleStep;
            componentDepth = c.componentDepth;
            scaleMin = c.scaleMin;
            scaleMax = c.scaleMax;
            sizeNoteTolerance = c.sizeNoteTolerance;
            maxRotationDegrees = c.maxRotationDegrees;
            maxTranslation = c.maxTranslation;
            offCentreNote = c.offCentreNote;
            icpIterations = c.icpIterations;
            icpMaxPairDistance = c.icpMaxPairDistance;
            passThreshold = c.passThreshold;
            minStrokeCoverage = c.minStrokeCoverage;
            unsureThreshold = c.unsureThreshold;
            missingCoverage = c.missingCoverage;
            unsureLengthChange = c.unsureLengthChange;
            unsureLengthGrowth = c.unsureLengthGrowth;
            unsureLengthDifference = c.unsureLengthDifference;
            unsureRotationDegrees = c.unsureRotationDegrees;
            unsureRotationMinLength = c.unsureRotationMinLength;
            clampPenalty = c.clampPenalty;
            strayMinArea = c.strayMinArea;
            strayPenalty = c.strayPenalty;
            inkThreshold = c.inkThreshold;
            canvasSize = c.canvasSize;
            scoring = c.scoring;
            snap = c.snap;
            language = c.language;
            referenceOverrides = c.referenceOverrides;
        }

        /**
         * Sets the field of the same name.
         *
         * @param v τ
         * @return this builder
         */
        public Builder tolerance(double v) { tolerance = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v credit falloff (1 = hard cutoff at τ)
         * @return this builder
         */
        public Builder creditFalloff(double v) { creditFalloff = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v stray distance
         * @return this builder
         */
        public Builder strayDistance(double v) { strayDistance = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v sample step
         * @return this builder
         */
        public Builder sampleStep(double v) { sampleStep = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v component depth
         * @return this builder
         */
        public Builder componentDepth(int v) { componentDepth = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v minimum scale
         * @return this builder
         */
        public Builder scaleMin(double v) { scaleMin = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v maximum scale
         * @return this builder
         */
        public Builder scaleMax(double v) { scaleMax = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v size-note tolerance (a fraction, e.g. 0.15)
         * @return this builder
         */
        public Builder sizeNoteTolerance(double v) { sizeNoteTolerance = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v maximum rotation in degrees
         * @return this builder
         */
        public Builder maxRotationDegrees(double v) { maxRotationDegrees = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v maximum translation
         * @return this builder
         */
        public Builder maxTranslation(double v) { maxTranslation = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v off-centre note threshold
         * @return this builder
         */
        public Builder offCentreNote(double v) { offCentreNote = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v ICP iterations
         * @return this builder
         */
        public Builder icpIterations(int v) { icpIterations = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v ICP pair distance
         * @return this builder
         */
        public Builder icpMaxPairDistance(double v) { icpMaxPairDistance = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v pass threshold
         * @return this builder
         */
        public Builder passThreshold(double v) { passThreshold = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v minimum stroke coverage
         * @return this builder
         */
        public Builder minStrokeCoverage(double v) { minStrokeCoverage = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v unsure threshold
         * @return this builder
         */
        public Builder unsureThreshold(double v) { unsureThreshold = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v missing-stroke coverage
         * @return this builder
         */
        public Builder missingCoverage(double v) { missingCoverage = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v stroke length change that makes a drawing UNSURE
         * @return this builder
         */
        public Builder unsureLengthChange(double v) { unsureLengthChange = v; return this; }
        /**
         * Sets {@code unsureLengthGrowth}.
         *
         * @param v stroke lengthening that makes a drawing UNSURE
         * @return this builder
         */
        public Builder unsureLengthGrowth(double v) { unsureLengthGrowth = v; return this; }
        /**
         * Sets {@code unsureLengthDifference}.
         *
         * @param v reference units a stroke's length must also be off by
         * @return this builder
         */
        public Builder unsureLengthDifference(double v) { unsureLengthDifference = v; return this; }
        /**
         * Sets {@code unsureRotationDegrees}.
         *
         * @param v turn of a long stroke that makes a drawing UNSURE
         * @return this builder
         */
        public Builder unsureRotationDegrees(double v) { unsureRotationDegrees = v; return this; }
        /**
         * Sets {@code unsureRotationMinLength}.
         *
         * @param v model length from which a stroke's turn is checked
         * @return this builder
         */
        public Builder unsureRotationMinLength(double v) { unsureRotationMinLength = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v clamp penalty
         * @return this builder
         */
        public Builder clampPenalty(double v) { clampPenalty = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v minimum stray area
         * @return this builder
         */
        public Builder strayMinArea(double v) { strayMinArea = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v stray penalty
         * @return this builder
         */
        public Builder strayPenalty(double v) { strayPenalty = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v ink threshold
         * @return this builder
         */
        public Builder inkThreshold(int v) { inkThreshold = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v canvas size in pixels (0 = image width)
         * @return this builder
         */
        public Builder canvasSize(int v) { canvasSize = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v reference overrides
         * @return this builder
         */
        public Builder referenceOverrides(Map<String, String> v) { referenceOverrides = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v scoring mode
         * @return this builder
         */
        public Builder scoring(Scoring v) { scoring = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v snapping settings
         * @return this builder
         */
        public Builder snap(SnapConfig v) { snap = v; return this; }
        /**
         * Sets the field of the same name.
         *
         * @param v output language
         * @return this builder
         */
        public Builder language(Language v) { language = v; return this; }

        /**
         * Creates the config.
         *
         * @return the config
         */
        public CheckerConfig build() {
            return new CheckerConfig(tolerance, creditFalloff, strayDistance, sampleStep, componentDepth, scaleMin, scaleMax, sizeNoteTolerance,
                    maxRotationDegrees, maxTranslation, offCentreNote, icpIterations, icpMaxPairDistance, passThreshold,
                    minStrokeCoverage, unsureThreshold, missingCoverage, unsureLengthChange, unsureLengthGrowth, unsureLengthDifference, unsureRotationDegrees, unsureRotationMinLength, clampPenalty, strayMinArea, strayPenalty, inkThreshold, canvasSize, scoring, snap, language,
                    referenceOverrides);
        }
    }
}
