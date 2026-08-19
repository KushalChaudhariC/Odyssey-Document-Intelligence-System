package com.calfuslearning.docusense_backend.service;

import com.calfuslearning.docusense_backend.dto.DocumentSummary;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Tracks which documents have been ingested. Kept intentionally simple - no database - each
 * document's summary is persisted as a small sidecar JSON file next to its PDF, and mirrored
 * in memory for fast reads. This also backs the semantic cache's coarse invalidation signal
 * (see corpusVersion()).
 */
@Service
public class DocumentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(DocumentCatalogService.class);

    private final Path uploadDir;
    private final ObjectMapper objectMapper;
    private final Map<String, DocumentSummary> catalog = new ConcurrentHashMap<>();

    public DocumentCatalogService(@Value("${docusense.storage.upload-dir}") String uploadDir, ObjectMapper objectMapper) {
        this.uploadDir = Path.of(uploadDir);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadFromDisk() {
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create upload directory: " + uploadDir, e);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(uploadDir, "*.json")) {
            for (Path metadataFile : stream) {
                DocumentSummary summary = objectMapper.readValue(metadataFile.toFile(), DocumentSummary.class);
                catalog.put(summary.sourceFileId(), summary);
            }
        } catch (IOException | JacksonException e) {
            log.warn("Could not fully load document catalog from {}", uploadDir, e);
        }
        log.info("Loaded {} previously ingested document(s) from catalog", catalog.size());
    }

    public DocumentSummary register(String sourceFileId, String fileName, int chunkCount) {
        DocumentSummary summary = new DocumentSummary(sourceFileId, fileName, chunkCount, Instant.now());
        catalog.put(sourceFileId, summary);
        try {
            objectMapper.writeValue(uploadDir.resolve(sourceFileId + ".json").toFile(), summary);
        } catch (JacksonException e) {
            log.warn("Could not persist catalog entry for {}", sourceFileId, e);
        }
        return summary;
    }

    public List<DocumentSummary> listAll() {
        return catalog.values().stream()
                .sorted(Comparator.comparing(DocumentSummary::ingestedAt).reversed())
                .toList();
    }

    public boolean contains(String sourceFileId) {
        return catalog.containsKey(sourceFileId);
    }

    /** Coarse-grained version of the whole corpus: total chunks ever ingested. Used to invalidate stale cache hits. */
    public int corpusVersion() {
        return catalog.values().stream().mapToInt(DocumentSummary::chunkCount).sum();
    }
}
