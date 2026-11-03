package kanji.handwriting.check.model;

/** Outcome of checking one reference stroke against the ink. */
public enum StrokeStatus {
    OK,
    MISSING,            // little or no ink under the reference path
    WRONG_ORIENTATION,  // ink present but slant/direction differs beyond tolerance
    WRONG_SHAPE,        // length/curvature mismatch
    UNCERTAIN           // registration too poor to judge
}
