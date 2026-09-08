package com.calfuslearning.docusense_backend.service;

import com.calfuslearning.docusense_backend.dto.Citation;
import com.calfuslearning.docusense_backend.dto.QueryResponse;
import com.calfuslearning.docusense_backend.exception.UpstreamServiceException;
import com.calfuslearning.docusense_backend.service.ai.DocumentQaAssistant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Orchestrates the question-answering pipeline: semantic cache check, retrieval, generation, confidence. */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private static final int TOP_K = 5;
    private static final int DEEP_DIVE_TOP_K = 10;
    private static final double HIGH_CONFIDENCE = 0.75;
    private static final double MEDIUM_CONFIDENCE = 0.55;
    /** Chunks scoring below this are considered irrelevant noise and dropped before generation/citation. */
    private static final double MIN_RELEVANCE_SCORE = MEDIUM_CONFIDENCE;

    private final EmbeddingModel embeddingModel;
    private final DocumentQaAssistant documentQaAssistant;
    private final EmbeddingStore<TextSegment> documentsStore;
    private final EmbeddingStore<TextSegment> queryCacheStore;
    private final DocumentCatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final double cacheSimilarityThreshold;

    public QueryService(
            EmbeddingModel embeddingModel,
            DocumentQaAssistant documentQaAssistant,
            @Qualifier("documentsStore") EmbeddingStore<TextSegment> documentsStore,
            @Qualifier("queryCacheStore") EmbeddingStore<TextSegment> queryCacheStore,
            DocumentCatalogService catalogService,
            ObjectMapper objectMapper,
            @Value("${docusense.cache.similarity-threshold}") double cacheSimilarityThreshold) {
        this.embeddingModel = embeddingModel;
        this.documentQaAssistant = documentQaAssistant;
        this.documentsStore = documentsStore;
        this.queryCacheStore = queryCacheStore;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
        this.cacheSimilarityThreshold = cacheSimilarityThreshold;
    }

    public QueryResponse answer(String question) {
        log.info("Answering question ({} chars)", question.length());
        Embedding questionEmbedding = embed(question);
        int corpusVersion = catalogService.corpusVersion();
        log.debug("Current corpus version: {}", corpusVersion);

        QueryResponse cached = checkCache(questionEmbedding, corpusVersion);
        if (cached != null) {
            log.info("Query served from semantic cache");
            return cached;
        }

        log.info("No valid cache hit, running full retrieval + generation pipeline");
        return runFullPipeline(question, questionEmbedding, corpusVersion);
    }

    private Embedding embed(String text) {
        try {
            Embedding embedding = embeddingModel.embed(text).content();
            log.debug("Embedded text into vector of dimension {}", embedding.vector().length);
            return embedding;
        } catch (Exception e) {
            log.error("Failed to embed question", e);
            throw new UpstreamServiceException("Could not process the question text.", e);
        }
    }

    private QueryResponse checkCache(Embedding questionEmbedding, int corpusVersion) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(1)
                .minScore(cacheSimilarityThreshold)
                .build();

        List<EmbeddingMatch<TextSegment>> matches;
        try {
            matches = queryCacheStore.search(request).matches();
        } catch (Exception e) {
            log.warn("Semantic cache lookup failed, falling back to full pipeline", e);
            return null;
        }
        if (matches.isEmpty()) {
            log.debug("No semantic cache entry above similarity threshold {}", cacheSimilarityThreshold);
            return null;
        }

        Metadata metadata = matches.get(0).embedded().metadata();
        Integer cachedCorpusVersion = metadata.getInteger("corpusVersion");
        if (cachedCorpusVersion == null || cachedCorpusVersion != corpusVersion) {
            log.info("Cache hit found but source documents changed since caching (cached v{}, current v{}); ignoring stale entry",
                    cachedCorpusVersion, corpusVersion);
            return null;
        }

        try {
            List<Citation> citations = objectMapper.readValue(
                    metadata.getString("citationsJson"), new TypeReference<List<Citation>>() {});
            return new QueryResponse(
                    metadata.getString("answer"),
                    citations,
                    metadata.getDouble("confidence"),
                    metadata.getString("confidenceLabel"),
                    true);
        } catch (Exception e) {
            log.warn("Cached entry could not be parsed, ignoring it", e);
            return null;
        }
    }

    /**
     * A wider, second-chance retrieval pass for when the normal top-5 pipeline came back with
     * Medium/Low confidence. Retrieves top-{@value #DEEP_DIVE_TOP_K} chunks for both the original
     * question and an LLM-rephrased version of it, merges and dedupes the two result sets, then
     * generates an answer from that larger pool using the exact same prompt as the normal flow.
     * If the resulting confidence beats what the normal top-5 pipeline would have found, the
     * cached entry for this question is overwritten so later askers get the better answer directly.
     */
    public QueryResponse deepDive(String question) {
        log.info("Running deep-dive pipeline for question ({} chars)", question.length());
        Embedding questionEmbedding = embed(question);
        int corpusVersion = catalogService.corpusVersion();

        List<EmbeddingMatch<TextSegment>> baselineMatches = retrieveTopChunks(questionEmbedding, TOP_K);
        double baselineTopScore = baselineMatches.isEmpty() ? 0.0 : baselineMatches.get(0).score();

        List<EmbeddingMatch<TextSegment>> wideMatches = retrieveTopChunks(questionEmbedding, DEEP_DIVE_TOP_K);

        String rephrasedQuestion = rephraseQuestion(question);
        List<EmbeddingMatch<TextSegment>> rephrasedMatches = List.of();
        if (rephrasedQuestion != null) {
            Embedding rephrasedEmbedding = embed(rephrasedQuestion);
            rephrasedMatches = retrieveTopChunks(rephrasedEmbedding, DEEP_DIVE_TOP_K);
        }

        List<EmbeddingMatch<TextSegment>> mergedMatches = mergeAndDedupe(wideMatches, rephrasedMatches);
        log.info("Deep-dive merged {} wide + {} rephrased match(es) into {} unique chunk(s)",
                wideMatches.size(), rephrasedMatches.size(), mergedMatches.size());

        if (mergedMatches.isEmpty()) {
            log.info("Deep-dive found no chunks above the relevance floor ({})", MIN_RELEVANCE_SCORE);
            return new QueryResponse(
                    "I couldn't find any relevant information in the ingested documents to answer this question.",
                    List.of(), 0.0, "Low", false);
        }

        String answer = generateAnswer(question, mergedMatches);
        List<Citation> citations = buildCitations(mergedMatches);
        double topScore = mergedMatches.get(0).score();
        String confidenceLabel = confidenceLabel(topScore);
        log.info("Deep-dive top relevance score {} ({} confidence) vs baseline top-{} score {}",
                topScore, confidenceLabel, TOP_K, baselineTopScore);

        QueryResponse response = new QueryResponse(answer, citations, topScore, confidenceLabel, false);

        if (topScore > baselineTopScore) {
            log.info("Deep-dive improved on the baseline confidence; overwriting cached entry for this question");
            overwriteCache(question, questionEmbedding, response, corpusVersion);
        } else {
            log.info("Deep-dive did not improve on baseline confidence; not caching");
        }

        return response;
    }

    private String rephraseQuestion(String question) {
        try {
            String rephrased = documentQaAssistant.rephrase(question);
            log.debug("Rephrased question for deep-dive retrieval: {}", rephrased);
            return rephrased;
        } catch (Exception e) {
            log.warn("Failed to rephrase question for deep-dive (continuing with wide retrieval only)", e);
            return null;
        }
    }

    /** Dedupes by embeddingId, keeping the higher score when a chunk appears in both lists, sorted best-first. */
    private List<EmbeddingMatch<TextSegment>> mergeAndDedupe(
            List<EmbeddingMatch<TextSegment>> first, List<EmbeddingMatch<TextSegment>> second) {
        Map<String, EmbeddingMatch<TextSegment>> byId = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : first) {
            byId.put(match.embeddingId(), match);
        }
        for (EmbeddingMatch<TextSegment> match : second) {
            byId.merge(match.embeddingId(), match, (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
        }
        return byId.values().stream()
                .sorted(Comparator.comparingDouble((EmbeddingMatch<TextSegment> m) -> m.score()).reversed())
                .toList();
    }

    private QueryResponse runFullPipeline(String question, Embedding questionEmbedding, int corpusVersion) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(questionEmbedding, TOP_K);

        if (matches.isEmpty()) {
            log.info("No chunks scored above the relevance floor ({}); treating as no answer available", MIN_RELEVANCE_SCORE);
            return new QueryResponse(
                    "I couldn't find any relevant information in the ingested documents to answer this question.",
                    List.of(), 0.0, "Low", false);
        }

        String answer = generateAnswer(question, matches);
        List<Citation> citations = buildCitations(matches);
        double topScore = matches.get(0).score();
        String confidenceLabel = confidenceLabel(topScore);
        log.info("Generated answer with top relevance score {} ({} confidence)", topScore, confidenceLabel);

        QueryResponse response = new QueryResponse(answer, citations, topScore, confidenceLabel, false);
        storeInCache(question, questionEmbedding, response, corpusVersion);
        return response;
    }

    private List<EmbeddingMatch<TextSegment>> retrieveTopChunks(Embedding questionEmbedding, int topK) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(topK)
                .minScore(MIN_RELEVANCE_SCORE)
                .build();
        try {
            EmbeddingSearchResult<TextSegment> result = documentsStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = dropOrphanedChunks(result.matches());
            log.info("Retrieved {} chunk(s) scoring >= {} for question (top-{})", matches.size(), MIN_RELEVANCE_SCORE, topK);
            return matches;
        } catch (Exception e) {
            log.error("Retrieval from vector database failed", e);
            throw new UpstreamServiceException("Could not search the document library.", e);
        }
    }

    /**
     * Drops chunks whose source document is no longer in the catalog. This can happen when a
     * document is re-uploaded (a fresh sourceFileId/chunk set) and the old PDF/catalog entry is
     * removed without the old chunks being cleaned out of Weaviate too - without this filter,
     * those stale chunks keep getting cited and their "View Source" link 404s since the file is gone.
     */
    private List<EmbeddingMatch<TextSegment>> dropOrphanedChunks(List<EmbeddingMatch<TextSegment>> matches) {
        return matches.stream()
                .filter(match -> {
                    String sourceFileId = match.embedded().metadata().getString("sourceFileId");
                    boolean valid = catalogService.contains(sourceFileId);
                    if (!valid) {
                        log.warn("Dropping orphaned chunk for sourceFileId={} (no longer in catalog - source document was likely replaced or deleted)", sourceFileId);
                    }
                    return valid;
                })
                .toList();
    }

    private String generateAnswer(String question, List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder excerpts = new StringBuilder();
        for (EmbeddingMatch<TextSegment> match : matches) {
            Metadata metadata = match.embedded().metadata();
            excerpts.append("[").append(metadata.getString("sourceFileName"))
                    .append(", page ").append(metadata.getInteger("pageNumber")).append("]\n")
                    .append(match.embedded().text()).append("\n\n");
        }

        log.debug("Calling chat model with {} excerpt(s)", matches.size());
        try {
            String answer = documentQaAssistant.answer(question, excerpts.toString());
            log.debug("Chat model returned an answer of {} chars", answer.length());
            return answer;
        } catch (Exception e) {
            log.error("Call to OpenAI chat model failed", e);
            throw new UpstreamServiceException(
                    "Could not get an answer from the AI model. Check that OPENAI_API_KEY is set correctly.", e);
        }
    }

    private List<Citation> buildCitations(List<EmbeddingMatch<TextSegment>> matches) {
        return matches.stream()
                .map(match -> {
                    Metadata metadata = match.embedded().metadata();
                    String sourceFileId = metadata.getString("sourceFileId");
                    return new Citation(
                            metadata.getString("sourceFileName"),
                            sourceFileId,
                            metadata.getInteger("pageNumber"),
                            snippet(match.embedded().text()),
                            "/api/documents/" + sourceFileId + "#page=" + metadata.getInteger("pageNumber"),
                            highlightBox(metadata));
                })
                .toList();
    }

    /** Null for chunks ingested before highlight tracking was added, or where no match was found on the page. */
    private Citation.HighlightBox highlightBox(Metadata metadata) {
        if (!metadata.containsKey("highlightX")) {
            return null;
        }
        return new Citation.HighlightBox(
                metadata.getDouble("highlightX"),
                metadata.getDouble("highlightY"),
                metadata.getDouble("highlightWidth"),
                metadata.getDouble("highlightHeight"));
    }

    private String snippet(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private String confidenceLabel(double score) {
        if (score >= HIGH_CONFIDENCE) return "High";
        if (score >= MEDIUM_CONFIDENCE) return "Medium";
        return "Low";
    }

    private void storeInCache(String question, Embedding questionEmbedding, QueryResponse response, int corpusVersion) {
        try {
            Metadata metadata = new Metadata()
                    .put("answer", response.answer())
                    .put("citationsJson", objectMapper.writeValueAsString(response.citations()))
                    .put("confidence", response.confidence())
                    .put("confidenceLabel", response.confidenceLabel())
                    .put("corpusVersion", corpusVersion)
                    .put("cachedAt", Instant.now().toString());
            queryCacheStore.add(questionEmbedding, TextSegment.from(question, metadata));
            log.debug("Stored answer in semantic cache for corpus v{}", corpusVersion);
        } catch (Exception e) {
            log.warn("Failed to store answer in semantic cache (non-fatal)", e);
        }
    }

    /** Removes any existing cache entry(ies) for this question before storing the improved deep-dive answer. */
    private void overwriteCache(String question, Embedding questionEmbedding, QueryResponse response, int corpusVersion) {
        try {
            EmbeddingSearchRequest existingRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(questionEmbedding)
                    .maxResults(5)
                    .minScore(cacheSimilarityThreshold)
                    .build();
            List<String> staleIds = queryCacheStore.search(existingRequest).matches().stream()
                    .map(EmbeddingMatch::embeddingId)
                    .toList();
            if (!staleIds.isEmpty()) {
                queryCacheStore.removeAll(staleIds);
                log.debug("Removed {} stale cache entry(ies) for this question before overwriting", staleIds.size());
            }
        } catch (Exception e) {
            log.warn("Failed to remove stale cache entries before overwrite (continuing anyway)", e);
        }
        storeInCache(question, questionEmbedding, response, corpusVersion);
    }
}
