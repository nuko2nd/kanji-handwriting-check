package kanji.handwriting.check.service;

import java.nio.file.Path;
import java.util.List;

import kanji.handwriting.check.model.Point;
import kanji.handwriting.check.model.ReferenceKanji;
import kanji.handwriting.check.model.StrokeResult;
import kanji.handwriting.check.model.Transform;

/** Per-stroke coverage, orientation, and shape checks, plus unexplained-ink detection. */
public class StrokeAnalyzer {
    public List<StrokeResult> analyzeStrokes(ReferenceKanji reference, Transform transform, Path binarizedCell) {
        throw new UnsupportedOperationException("TODO: sample path, compare ink tangent to reference tangent");
    }

    public List<Point> findUnexplainedInk(ReferenceKanji reference, Transform transform, Path binarizedCell) {
        throw new UnsupportedOperationException("TODO: mask out ink near reference strokes, return the rest");
    }
}
