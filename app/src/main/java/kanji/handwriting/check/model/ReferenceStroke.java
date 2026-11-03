package kanji.handwriting.check.model;

import java.util.List;

/** One KanjiVG stroke as a polyline in KanjiVG's 109x109 space. */
public record ReferenceStroke(int index, List<Point> polyline, String kvgType) {}
