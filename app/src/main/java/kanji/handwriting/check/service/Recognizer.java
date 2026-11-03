package kanji.handwriting.check.service;

import java.util.List;

import kanji.handwriting.check.model.Candidate;
import kanji.handwriting.check.model.Cell;

/** Optional. Proposes top-k kanji for a cell. Unused while a transcript is required. */
public interface Recognizer {
    List<Candidate> recognize(Cell cell, int topK);
}
