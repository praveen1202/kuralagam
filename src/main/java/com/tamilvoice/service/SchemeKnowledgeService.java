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
 * No external vector DB is required — this is intentionally
 * simple so it runs without extra infrastructure.
 * Phase 3 can replace this with a proper embedding/vector search.
 */
@Service
public class SchemeKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(SchemeKnowledgeService.class);

    /** Maximum number of scheme snippets to inject per query */
    private static final int MAX_RESULTS = 3;

    private record SchemeEntry(String id, String name, String tamilName,
                                String category, List<String> keywords, String summary) {}

    private final List<SchemeEntry> schemes = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void loadSchemes() {
        try {
            ClassPathResource resource = new ClassPathResource("schemes.json");
            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                for (JsonNode node : root) {
                    List<String> keywords = new ArrayList<>();
                    for (JsonNode kw : node.path("keywords")) {
                        keywords.add(kw.asText().toLowerCase(Locale.ROOT));
                    }
                    schemes.add(new SchemeEntry(
                            node.path("id").asText(),
                            node.path("name").asText(),
                            node.path("tamilName").asText(),
                            node.path("category").asText(),
                            keywords,
                            node.path("summary").asText()
                    ));
                }
            }
            log.info("SchemeKnowledgeService loaded {} schemes from schemes.json", schemes.size());
        } catch (Exception e) {
            log.error("Failed to load schemes.json — knowledge base will be empty: {}", e.getMessage());
        }
    }

    /**
     * Find schemes relevant to the user query and return them as a
     * formatted context block, or an empty string if nothing matches.
     *
     * Scoring: each keyword that appears in the query earns +1 point.
     * Scheme name or Tamil name appearing in the query earns +3 points.
     * Only schemes scoring > 0 are returned, capped at MAX_RESULTS.
     */
    public String findRelevantContext(String query) {
        if (query == null || query.isBlank() || schemes.isEmpty()) return "";

        String lowerQuery = query.toLowerCase(Locale.ROOT);

        record Scored(SchemeEntry scheme, int score) {}

        List<Scored> scored = schemes.stream()
                .map(s -> {
                    int score = 0;
                    for (String kw : s.keywords()) {
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

        if (scored.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("RELEVANT SCHEME FACTS (use these as authoritative grounding — do not contradict them):\n");
        for (Scored s : scored) {
            sb.append("• ").append(s.scheme().name())
              .append(" [").append(s.scheme().tamilName()).append("]:\n  ")
              .append(s.scheme().summary()).append("\n");
        }
        log.debug("Scheme context injected for query '{}': {} schemes (top score: {})",
                query.length() > 60 ? query.substring(0, 60) + "..." : query,
                scored.size(),
                scored.get(0).score());
        return sb.toString();
    }

    /** Number of schemes loaded (for health checks / logging) */
    public int schemeCount() {
        return schemes.size();
    }
}
