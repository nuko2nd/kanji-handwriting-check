package kanji.handwriting.check.model;

import java.nio.file.Path;

/** One extracted grid cell: where it was on the page and where its image is stored. */
public record Cell(int index, Path imagePath) {}
