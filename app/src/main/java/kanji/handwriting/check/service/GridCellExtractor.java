package kanji.handwriting.check.service;

import kanji.handwriting.check.model.Cell;
import java.nio.file.Path;
import java.util.List;

/** Extracts cells from the printed/underlay grid using known cell geometry. */
public class GridCellExtractor implements CellExtractor {
    @Override
    public List<Cell> extract(Path normalizedPage, int expectedCount) {
        throw new UnsupportedOperationException("TODO: crop known grid cells, skip empty ones");
    }
}
