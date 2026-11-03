package kanji.handwriting.check.model;

import java.nio.file.Path;
import java.util.List;

/** Analysis of a single written character. Immutable; corrections produce a new instance. */
public record CharacterResult(
        int index,
        String targetKanji,
        double recognitionConfidence,   // 1.0 when the target came from a user transcript
        Path cellImagePath,             // stored binarized crop, needed for re-analysis
        double registrationQuality,     // 0..1; low means "couldn't align reliably"
        List<StrokeResult> strokes,
        List<Point> unexplainedInk) {   // ink not near any reference stroke (extra-stroke signal)

    public CharacterResult withTarget(String newKanji, double newRegistrationQuality,
                                      List<StrokeResult> newStrokes, List<Point> newUnexplained) {
        return new CharacterResult(index, newKanji, 1.0, cellImagePath,
                newRegistrationQuality, newStrokes, newUnexplained);
    }
}
