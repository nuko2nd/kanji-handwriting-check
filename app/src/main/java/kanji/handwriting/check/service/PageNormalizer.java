package kanji.handwriting.check.service;

import java.nio.file.Path;

/** Detects corner markers, applies a perspective warp, and evens out lighting. */
public class PageNormalizer {
    /** @return path to a rectified, lighting-normalized page image */
    public Path normalize(Path photo) {
        throw new UnsupportedOperationException("TODO: marker detection + perspective warp");
    }
}
