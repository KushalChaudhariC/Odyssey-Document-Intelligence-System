package com.calfuslearning.docusense_backend.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.weaviate.WeaviateEmbeddingStore;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the RAG building blocks: the local embedding model, the OpenAI chat model, and two
 * separate Weaviate object classes - one for the real document chunks, one for the semantic
 * answer cache.
 */
@Configuration
public class RagBeansConfig {

    public static final String DOCUMENTS_CLASS = "Documents";
    public static final String QUERY_CACHE_CLASS = "QueryCache";

    @Value("${weaviate.scheme}")
    private String weaviateScheme;

    @Value("${weaviate.host}")
    private String weaviateHost;

    @Value("${weaviate.port}")
    private int weaviatePort;

    @Value("${openai.api.key}")
    private String openAiApiKey;

    @Value("${openai.chat.model}")
    private String openAiChatModel;

    /** Runs fully in-process via ONNX - no API key, no network call. */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(openAiApiKey == null || openAiApiKey.isBlank() ? "not-configured" : openAiApiKey)
                .modelName(openAiChatModel)
                .timeout(Duration.ofSeconds(45))
                .maxRetries(2)
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> documentsStore() {
        return WeaviateEmbeddingStore.builder()
                .scheme(weaviateScheme)
                .host(weaviateHost)
                .port(weaviatePort)
                .objectClass(DOCUMENTS_CLASS)
                .avoidDups(true)
                .textFieldName("chunkText")
                .metadataKeys(List.of("sourceFileName", "sourceFileId", "pageNumber", "chunkIndex", "ingestedAt"))
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> queryCacheStore() {
        return WeaviateEmbeddingStore.builder()
                .scheme(weaviateScheme)
                .host(weaviateHost)
                .port(weaviatePort)
                .objectClass(QUERY_CACHE_CLASS)
                .avoidDups(false)
                .textFieldName("question")
                .metadataKeys(List.of("answer", "citationsJson", "confidence", "confidenceLabel", "corpusVersion", "cachedAt"))
                .build();
    }
}
