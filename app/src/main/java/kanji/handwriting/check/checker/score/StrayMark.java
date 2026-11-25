package kanji.handwriting.check.checker.score;

import kanji.handwriting.check.model.Point;

/**
 * A connected patch of ink lying farther than the stray distance from every reference stroke.
 *
 * @param centroid centre of the patch, in reference space
 * @param area patch area in square reference units
 * @param nearestComponent label of the component it is attributed to
 */
public record StrayMark(Point centroid, double area, String nearestComponent) {}
