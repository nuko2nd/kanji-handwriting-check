package kanji.handwriting.check.service;

import java.nio.file.Path;
import java.util.List;

import kanji.handwriting.check.model.Cell;

/** Splits a normalized page into per-character cells. Swap implementations for different paper types. */
public interface CellExtractor {
    /** @param expectedCount number of characters expected (from transcript); may be -1 if unknown */
    List<Cell> extract(Path normalizedPage, int expectedCount);
}
