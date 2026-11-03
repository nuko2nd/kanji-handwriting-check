package kanji.handwriting.check.service;

import kanji.handwriting.check.model.ReferenceKanji;
import kanji.handwriting.check.model.Transform;
import java.nio.file.Path;

/** Aligns the reference onto the ink. Riskiest step; always report quality. */
public class Registrar {
    public Transform register(ReferenceKanji reference, Path binarizedCell) {
        throw new UnsupportedOperationException("TODO: affine fit, optional non-rigid refinement");
    }
}
