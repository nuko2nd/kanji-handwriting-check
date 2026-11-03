package kanji.handwriting.check.service;

import kanji.handwriting.check.model.AnalysisSession;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory sessions; cell images live on disk. Doubles as the start of your labeled dataset. */
public class SessionStore {
    private final Map<String, AnalysisSession> sessions = new ConcurrentHashMap<>();

    public void save(AnalysisSession session) { sessions.put(session.id(), session); }
    public Optional<AnalysisSession> find(String id) { return Optional.ofNullable(sessions.get(id)); }
}
