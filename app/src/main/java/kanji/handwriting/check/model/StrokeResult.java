package kanji.handwriting.check.model;

import java.util.List;

/**
 * Verdict for one reference stroke.
 * registeredPath is the reference stroke warped into cell-image coordinates, so the UI can draw it directly.
 */
public record StrokeResult(
        int referenceStrokeIndex,
        StrokeStatus status,
        double confidence,
        String message,
        List<Point> registeredPath) {}
