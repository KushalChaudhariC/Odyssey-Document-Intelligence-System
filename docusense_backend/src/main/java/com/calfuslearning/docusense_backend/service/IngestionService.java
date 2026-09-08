package com.calfuslearning.docusense_backend.service;

import com.calfuslearning.docusense_backend.dto.UploadResponse;
import com.calfuslearning.docusense_backend.exception.BadRequestException;
import com.calfuslearning.docusense_backend.exception.UnsupportedFileTypeException;
import com.calfuslearning.docusense_backend.exception.UpstreamServiceException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Turns an uploaded PDF into embedded, searchable chunks in the Documents Weaviate class. */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;

    private final PdfStorageService pdfStorageService;
    private final DocumentCatalogService catalogService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> documentsStore;
    private final EmbeddingStore<TextSegment> queryCacheStore;

    public IngestionService(
            PdfStorageService pdfStorageService,
            DocumentCatalogService catalogService,
            EmbeddingModel embeddingModel,
            @Qualifier("documentsStore") EmbeddingStore<TextSegment> documentsStore,
            @Qualifier("queryCacheStore") EmbeddingStore<TextSegment> queryCacheStore) {
        this.pdfStorageService = pdfStorageService;
        this.catalogService = catalogService;
        this.embeddingModel = embeddingModel;
        this.documentsStore = documentsStore;
        this.queryCacheStore = queryCacheStore;
    }

    public UploadResponse ingest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file was uploaded, or the uploaded file is empty.");
        }
        String originalName = file.getOriginalFilename() == null ? "document.pdf" : file.getOriginalFilename();
        if (!originalName.toLowerCase().endsWith(".pdf")
                || (file.getContentType() != null && !file.getContentType().equals("application/pdf"))) {
            throw new UnsupportedFileTypeException("Only PDF files are supported. Received: " + originalName);
        }

        String sourceFileId = UUID.randomUUID().toString();
        log.info("Ingesting upload '{}' as sourceFileId={}", originalName, sourceFileId);

        pdfStorageService.save(sourceFileId, file);

        List<TextSegment> chunks = extractAndSplit(sourceFileId, originalName);
        if (chunks.isEmpty()) {
            log.warn("No extractable text found in '{}' ({})", originalName, sourceFileId);
        }

        embedAndStore(chunks);
        invalidateQueryCache();

        catalogService.register(sourceFileId, originalName, chunks.size());
        log.info("Ingestion complete for '{}': {} chunk(s) stored", originalName, chunks.size());
        return new UploadResponse(sourceFileId, originalName, chunks.size());
    }

    /**
     * Wipes the entire semantic query cache after every successful ingestion. A newly uploaded or
     * revised document can make previously cached answers outdated, and figuring out which cached
     * entries are actually affected is unreliable - a full wipe on every upload is simpler and
     * safer than a selective invalidation that might miss something.
     */
    private void invalidateQueryCache() {
        try {
            queryCacheStore.removeAll();
            log.info("Cleared semantic query cache after ingestion");
        } catch (Exception e) {
            log.warn("Failed to clear semantic query cache after ingestion (non-fatal)", e);
        }
    }

    private List<TextSegment> extractAndSplit(String sourceFileId, String originalName) {
        DocumentSplitter splitter = DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);
        List<TextSegment> allChunks = new ArrayList<>();
        Instant ingestedAt = Instant.now();

        try (PDDocument pdf = Loader.loadPDF(pdfStorageService.loadAsResource(sourceFileId).getFile())) {
            int pageCount = pdf.getNumberOfPages();

            for (int pageIndex = 1; pageIndex <= pageCount; pageIndex++) {
                PositionTrackingTextStripper stripper = new PositionTrackingTextStripper();
                stripper.setStartPage(pageIndex);
                stripper.setEndPage(pageIndex);
                stripper.getText(pdf);
                String pageText = stripper.text();
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }

                PDPage page = pdf.getPage(pageIndex - 1);
                float pageWidth = page.getMediaBox().getWidth();
                float pageHeight = page.getMediaBox().getHeight();

                Document pageDocument = Document.from(pageText);
                List<TextSegment> pageChunks = splitter.split(pageDocument);

                int searchFrom = 0;
                for (int chunkIndex = 0; chunkIndex < pageChunks.size(); chunkIndex++) {
                    TextSegment chunk = pageChunks.get(chunkIndex);
                    Metadata metadata = new Metadata()
                            .put("sourceFileName", originalName)
                            .put("sourceFileId", sourceFileId)
                            .put("pageNumber", pageIndex)
                            .put("chunkIndex", chunkIndex)
                            .put("ingestedAt", ingestedAt.toString());

                    int matchStart = pageText.indexOf(chunk.text(), Math.max(0, searchFrom - CHUNK_OVERLAP));
                    if (matchStart >= 0) {
                        searchFrom = matchStart + chunk.text().length();
                        addHighlightBox(metadata, stripper.positions(), matchStart, matchStart + chunk.text().length(),
                                pageWidth, pageHeight);
                    }

                    allChunks.add(TextSegment.from(chunk.text(), metadata));
                }
            }
        } catch (IOException e) {
            log.error("Failed to parse PDF '{}' ({})", originalName, sourceFileId, e);
            throw new UpstreamServiceException("Could not read the uploaded PDF. It may be corrupted or encrypted.", e);
        }

        return allChunks;
    }

    /**
     * Computes the union bounding box of every character in [startIndex, endIndex) and stores it
     * in the chunk's metadata as page-relative fractions (0-1), so the frontend can draw a
     * highlight rectangle without needing to know the page's actual point dimensions. Silently
     * does nothing if no positions are available in that range (e.g. the chunk text couldn't be
     * located in the page, or fell entirely on separator placeholders).
     */
    private void addHighlightBox(
            Metadata metadata, List<TextPosition> positions, int startIndex, int endIndex, float pageWidth, float pageHeight) {
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        boolean found = false;

        int safeEnd = Math.min(endIndex, positions.size());
        for (int i = Math.max(0, startIndex); i < safeEnd; i++) {
            TextPosition tp = positions.get(i);
            if (tp == null) {
                continue;
            }
            found = true;
            left = Math.min(left, tp.getXDirAdj());
            top = Math.min(top, tp.getYDirAdj() - tp.getHeightDir());
            right = Math.max(right, tp.getXDirAdj() + tp.getWidthDirAdj());
            bottom = Math.max(bottom, tp.getYDirAdj());
        }

        if (!found || pageWidth <= 0 || pageHeight <= 0) {
            return;
        }

        metadata.put("highlightX", left / pageWidth)
                .put("highlightY", top / pageHeight)
                .put("highlightWidth", (right - left) / pageWidth)
                .put("highlightHeight", (bottom - top) / pageHeight);
    }

    private void embedAndStore(List<TextSegment> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        try {
            List<Embedding> embeddings = embeddingModel.embedAll(chunks).content();
            documentsStore.addAll(embeddings, chunks);
        } catch (Exception e) {
            log.error("Failed to embed/store {} chunk(s)", chunks.size(), e);
            throw new UpstreamServiceException("Could not store the document in the vector database.", e);
        }
    }

    /**
     * Full reset: wipes both Weaviate classes (documents + semantic cache), every stored PDF, and
     * the catalog. Meant for testing/debugging so the corpus and vector store can never drift out
     * of sync with each other.
     */
    public void resetAll() {
        log.warn("Resetting entire corpus: clearing vector stores, catalog, and stored files");

        try {
            documentsStore.removeAll();
            log.info("Cleared all embeddings from the documents vector store");
        } catch (Exception e) {
            log.error("Failed to clear documents vector store", e);
            throw new UpstreamServiceException("Could not clear the document vector store.", e);
        }

        try {
            queryCacheStore.removeAll();
            log.info("Cleared all entries from the semantic query cache");
        } catch (Exception e) {
            log.error("Failed to clear semantic query cache", e);
            throw new UpstreamServiceException("Could not clear the semantic query cache.", e);
        }

        pdfStorageService.deleteAll();
        catalogService.clearAll();
        log.info("Reset complete: corpus is now empty");
    }
}
