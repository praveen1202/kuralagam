package com.tamilvoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SchemeKnowledgeService — Phase 2: RAG-lite knowledge base
 *
 * Loads TN government Health and Education scheme data from
 * schemes.json (classpath resource) at startup.
 *
 * For each user query, it performs keyword scoring to find the
 * most relevant schemes and returns them as a grounded context
 * string that is injected into the Groq system prompt.
 *
 * Matching runs over two keyword sets:
 *   - keywords      : English and romanised-Tamil terms (typed input)
 *   - tamilKeywords : Tamil script terms (Whisper transcribes speech to
 *                     Tamil script, so without these the primary voice
 *                     path would never match a scheme)
 *
 * Every match carries a score. A low or zero score means the knowledge
 * base has a gap for that query — findRelevant() surfaces the score so
 * the caller can log it (see DataCaptureService.captureKnowledgeGap).
 *
 * No external vector DB is required — this is intentionally
 * simple so it runs without extra infrastructure.
 * Phase 3 can replace this with a proper embedding/vector search.
 */
@Service
public class SchemeKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(SchemeKnowledgeService.class);

    /** Maximum number of scheme snippets to inject per query */
    private static final int MAX_RESULTS = 3;

    /**
     * Scores below this are treated as a knowledge-base gap and logged for review.
     * A score of 1 means a single weak keyword brushed the query — usually not
     * enough to trust that we actually understood what was asked.
     */
    private static final int MIN_CONFIDENT_SCORE = 2;

    private record SchemeEntry(String id, String name, String tamilName,
                                String category, List<String> keywords,
                                List<String> tamilKeywords, String summary,
                                String sourceUrl) {}

    /**
     * Outcome of a knowledge-base lookup.
     *
     * @param context     formatted grounding block for the system prompt, or "" if nothing matched
     * @param topScore    score of the best-matching scheme (0 = nothing matched at all)
     * @param matchedIds  ids of the schemes injected, best first
     */
    public record MatchResult(String context, int topScore, List<String> matchedIds) {

        static final MatchResult EMPTY = new MatchResult("", 0, List.of());

        /** True when the knowledge base had nothing confident to say about this query. */
        public boolean isGap() {
            return topScore < MIN_CONFIDENT_SCORE;
        }
    }

    private final List<SchemeEntry> schemes = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void loadSchemes() {
        try {
            ClassPathResource resource = new ClassPathResource("schemes.json");
            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                for (JsonNode node : root) {
                    schemes.add(new SchemeEntry(
                            node.path("id").asText(),
                            node.path("name").asText(),
                            node.path("tamilName").asText(),
                            node.path("category").asText(),
                            readKeywords(node.path("keywords")),
                            readKeywords(node.path("tamilKeywords")),
                            node.path("summary").asText(),
                            node.path("sourceUrl").asText("")
                    ));
                }
            }
            log.info("SchemeKnowledgeService loaded {} schemes from schemes.json", schemes.size());
        } catch (Exception e) {
            log.error("Failed to load schemes.json — knowledge base will be empty: {}", e.getMessage());
        }
    }

    private List<String> readKeywords(JsonNode arrayNode) {
        List<String> keywords = new ArrayList<>();
        for (JsonNode kw : arrayNode) {
            keywords.add(kw.asText().toLowerCase(Locale.ROOT));
        }
        return keywords;
    }

    /**
     * Find schemes relevant to the user query and return them as a
     * formatted context block, or an empty string if nothing matches.
     *
     * Kept for callers that only need the prompt text; prefer
     * {@link #findRelevant(String)} when the match score matters.
     */
    public String findRelevantContext(String query) {
        return findRelevant(query).context();
    }

    /**
     * Score every scheme against the query and return the top matches
     * along with the winning score, so the caller can detect gaps.
     *
     * Scoring: each English or Tamil keyword appearing in the query earns +1.
     * The scheme name or Tamil name appearing in the query earns +3.
     * Only schemes scoring > 0 are returned, capped at MAX_RESULTS.
     */
    public MatchResult findRelevant(String query) {
        if (query == null || query.isBlank() || schemes.isEmpty()) return MatchResult.EMPTY;

        String lowerQuery = query.toLowerCase(Locale.ROOT);

        record Scored(SchemeEntry scheme, int score) {}

        List<Scored> scored = schemes.stream()
                .map(s -> {
                    int score = 0;
                    for (String kw : s.keywords()) {
                        if (lowerQuery.contains(kw)) score++;
                    }
                    for (String kw : s.tamilKeywords()) {
                        if (lowerQuery.contains(kw)) score++;
                    }
                    if (lowerQuery.contains(s.name().toLowerCase(Locale.ROOT)))      score += 3;
                    if (lowerQuery.contains(s.tamilName().toLowerCase(Locale.ROOT))) score += 3;
                    return new Scored(s, score);
                })
                .filter(s -> s.score() > 0)
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .limit(MAX_RESULTS)
                .toList();

        if (scored.isEmpty()) return MatchResult.EMPTY;

        StringBuilder sb = new StringBuilder();
        sb.append("RELEVANT SCHEME FACTS (use these as authoritative grounding — do not contradict them):\n");
        for (Scored s : scored) {
            sb.append("• ").append(s.scheme().name())
              .append(" [").append(s.scheme().tamilName()).append("]:\n  ")
              .append(s.scheme().summary()).append("\n");
            if (!s.scheme().sourceUrl().isBlank()) {
                sb.append("  Official source: ").append(s.scheme().sourceUrl()).append("\n");
            }
        }

        List<String> matchedIds = scored.stream().map(s -> s.scheme().id()).toList();
        int topScore = scored.get(0).score();

        log.debug("Scheme context injected for query '{}': {} schemes (top score: {})",
                truncate(query), scored.size(), topScore);

        return new MatchResult(sb.toString(), topScore, matchedIds);
    }

    private static String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    /** Number of schemes loaded (for health checks / logging) */
    public int schemeCount() {
        return schemes.size();
    }
}
