package com.calfuslearning.docusense_backend.service;

import com.calfuslearning.docusense_backend.dto.Citation;
import com.calfuslearning.docusense_backend.dto.QueryResponse;
import com.calfuslearning.docusense_backend.exception.UpstreamServiceException;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.time.Instant;
import java.util.List;
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
    private static final double HIGH_CONFIDENCE = 0.75;
    private static final double MEDIUM_CONFIDENCE = 0.55;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are DocuSense, an internal document assistant. Answer the user's question
            using ONLY the information in the provided document excerpts below. Do not use
            any outside knowledge. If the excerpts do not contain enough information to
            answer the question, say so clearly instead of guessing.

            For every factual claim you make, note which excerpt it came from.

            Document excerpts:
            %s
            """;

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final EmbeddingStore<TextSegment> documentsStore;
    private final EmbeddingStore<TextSegment> queryCacheStore;
    private final DocumentCatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final double cacheSimilarityThreshold;

    public QueryService(
            EmbeddingModel embeddingModel,
            ChatModel chatModel,
            @Qualifier("documentsStore") EmbeddingStore<TextSegment> documentsStore,
            @Qualifier("queryCacheStore") EmbeddingStore<TextSegment> queryCacheStore,
            DocumentCatalogService catalogService,
            ObjectMapper objectMapper,
            @Value("${docusense.cache.similarity-threshold}") double cacheSimilarityThreshold) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.documentsStore = documentsStore;
        this.queryCacheStore = queryCacheStore;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
        this.cacheSimilarityThreshold = cacheSimilarityThreshold;
    }

    public QueryResponse answer(String question) {
        Embedding questionEmbedding = embed(question);
        int corpusVersion = catalogService.corpusVersion();

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
            return embeddingModel.embed(text).content();
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
            return null;
        }

        Metadata metadata = matches.get(0).embedded().metadata();
        Integer cachedCorpusVersion = metadata.getInteger("corpusVersion");
        if (cachedCorpusVersion == null || cachedCorpusVersion != corpusVersion) {
            log.info("Cache hit found but source documents changed since caching; ignoring stale entry");
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

    private QueryResponse runFullPipeline(String question, Embedding questionEmbedding, int corpusVersion) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(questionEmbedding);

        if (matches.isEmpty()) {
            log.info("No relevant chunks found in document library for this question");
            return new QueryResponse(
                    "I couldn't find any relevant information in the ingested documents to answer this question.",
                    List.of(), 0.0, "Low", false);
        }

        String answer = generateAnswer(question, matches);
        List<Citation> citations = buildCitations(matches);
        double topScore = matches.get(0).score();
        String confidenceLabel = confidenceLabel(topScore);

        QueryResponse response = new QueryResponse(answer, citations, topScore, confidenceLabel, false);
        storeInCache(question, questionEmbedding, response, corpusVersion);
        return response;
    }

    private List<EmbeddingMatch<TextSegment>> retrieveTopChunks(Embedding questionEmbedding) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(TOP_K)
                .minScore(0.0)
                .build();
        try {
            EmbeddingSearchResult<TextSegment> result = documentsStore.search(request);
            log.info("Retrieved {} chunk(s) for question", result.matches().size());
            return result.matches();
        } catch (Exception e) {
            log.error("Retrieval from vector database failed", e);
            throw new UpstreamServiceException("Could not search the document library.", e);
        }
    }

    private String generateAnswer(String question, List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder excerpts = new StringBuilder();
        for (EmbeddingMatch<TextSegment> match : matches) {
            Metadata metadata = match.embedded().metadata();
            excerpts.append("[").append(metadata.getString("sourceFileName"))
                    .append(", page ").append(metadata.getInteger("pageNumber")).append("]\n")
                    .append(match.embedded().text()).append("\n\n");
        }

        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT_TEMPLATE.formatted(excerpts.toString())),
                UserMessage.from(question));

        try {
            ChatResponse response = chatModel.chat(messages);
            return response.aiMessage().text();
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
                            "/api/documents/" + sourceFileId + "#page=" + metadata.getInteger("pageNumber"));
                })
                .toList();
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
        } catch (Exception e) {
            log.warn("Failed to store answer in semantic cache (non-fatal)", e);
        }
    }
}
