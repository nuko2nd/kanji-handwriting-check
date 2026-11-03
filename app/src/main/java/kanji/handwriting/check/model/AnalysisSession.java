package kanji.handwriting.check.model;

import java.util.ArrayList;
import java.util.List;

/** One uploaded photo and its per-character results. */
public class AnalysisSession {
    private final String id;
    private final String transcript;   // null/empty when recognition was used
    private final List<CharacterResult> characters = new ArrayList<>();

    public AnalysisSession(String id, String transcript) {
        this.id = id;
        this.transcript = transcript;
    }

    public String id() { return id; }
    public String transcript() { return transcript; }
    public List<CharacterResult> characters() { return characters; }

    public void replaceCharacter(int index, CharacterResult updated) {
        characters.set(index, updated);
    }
}
