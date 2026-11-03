package kanji.handwriting.check.model;

/** Affine transform (2x3 matrix) mapping reference space to cell-image space, plus fit quality. */
public record Transform(double[] matrix2x3, double quality) {}
