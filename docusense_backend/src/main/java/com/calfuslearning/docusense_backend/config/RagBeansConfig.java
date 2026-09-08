package com.calfuslearning.docusense_backend.config;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.calfuslearning.docusense_backend.service.ai.DocumentQaAssistant;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.weaviate.WeaviateEmbeddingStore;


@Configuration
public class RagBeansConfig {

    private static final Logger log = LoggerFactory.getLogger(RagBeansConfig.class);

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
        boolean keyMissing = openAiApiKey == null || openAiApiKey.isBlank();
        if (keyMissing) {
            log.warn("OPENAI_API_KEY is not set - chat generation will fail until it is configured");
        } else {
            log.info("OpenAI chat model configured (model={})", openAiChatModel);
        }
        return OpenAiChatModel.builder()
                .apiKey(keyMissing ? "not-configured" : openAiApiKey)
                .modelName(openAiChatModel)
                .timeout(Duration.ofSeconds(45))
                .maxRetries(2)
                .build();
    }

    @Bean
    public DocumentQaAssistant documentQaAssistant(ChatModel chatModel) {
        return AiServices.builder(DocumentQaAssistant.class)
                .chatModel(chatModel)
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
                .metadataKeys(List.of(
                        "sourceFileName", "sourceFileId", "pageNumber", "chunkIndex", "ingestedAt",
                        "highlightX", "highlightY", "highlightWidth", "highlightHeight"))
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
