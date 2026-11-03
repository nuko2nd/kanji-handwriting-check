package kanji.handwriting.check.model;

import java.util.List;

/** Reference data for one kanji, strokes in correct order (also drives the animation). */
public record ReferenceKanji(String kanji, List<ReferenceStroke> strokes) {}
