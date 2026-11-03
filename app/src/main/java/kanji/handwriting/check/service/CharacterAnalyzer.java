package kanji.handwriting.check.service;

import kanji.handwriting.check.model.*;
import java.util.List;

/** Orchestrates reference lookup, registration, and stroke analysis for one cell. */
public class CharacterAnalyzer {
    private final ReferenceRepository references;
    private final Registrar registrar;
    private final StrokeAnalyzer strokeAnalyzer;

    public CharacterAnalyzer(ReferenceRepository references, Registrar registrar, StrokeAnalyzer strokeAnalyzer) {
        this.references = references;
        this.registrar = registrar;
        this.strokeAnalyzer = strokeAnalyzer;
    }

    /** Analyze a stored cell against a target kanji. Also used for re-analysis after a correction. */
    public CharacterResult analyze(Cell cell, String targetKanji, double recognitionConfidence) {
        throw new UnsupportedOperationException(
                "TODO: reference -> register -> if quality low mark strokes UNCERTAIN -> analyzeStrokes");
    }
}
