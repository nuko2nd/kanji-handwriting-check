package kanji.handwriting.check.model;

/** A recognition guess. Recognizers only propose; CharacterAnalyzer judges. */
public record Candidate(String kanji, double confidence) {}
