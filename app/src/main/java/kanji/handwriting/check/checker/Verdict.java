package kanji.handwriting.check.checker;

/** The outcome of checking a drawing. */
public enum Verdict {
    /** Drawn correctly (score at least {@code passThreshold} and every stroke well covered). */
    PASS,
    /**
     * Neither clearly right nor clearly wrong, typically messy but plausible handwriting whose
     * proportions stray far from the model. A person should check it.
     */
    UNSURE,
    /**
     * A definite error: a stroke missing, a short stroke written the wrong way, an extra mark, or a
     * very low score.
     */
    FAIL
}
