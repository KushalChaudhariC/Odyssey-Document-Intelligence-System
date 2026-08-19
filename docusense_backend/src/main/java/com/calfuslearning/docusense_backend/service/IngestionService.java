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
import org.apache.pdfbox.text.PDFTextStripper;
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

    public IngestionService(
            PdfStorageService pdfStorageService,
            DocumentCatalogService catalogService,
            EmbeddingModel embeddingModel,
            @Qualifier("documentsStore") EmbeddingStore<TextSegment> documentsStore) {
        this.pdfStorageService = pdfStorageService;
        this.catalogService = catalogService;
        this.embeddingModel = embeddingModel;
        this.documentsStore = documentsStore;
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

        catalogService.register(sourceFileId, originalName, chunks.size());
        log.info("Ingestion complete for '{}': {} chunk(s) stored", originalName, chunks.size());
        return new UploadResponse(sourceFileId, originalName, chunks.size());
    }

    private List<TextSegment> extractAndSplit(String sourceFileId, String originalName) {
        DocumentSplitter splitter = DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);
        List<TextSegment> allChunks = new ArrayList<>();
        Instant ingestedAt = Instant.now();

        try (PDDocument pdf = Loader.loadPDF(pdfStorageService.loadAsResource(sourceFileId).getFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = pdf.getNumberOfPages();

            for (int pageIndex = 1; pageIndex <= pageCount; pageIndex++) {
                stripper.setStartPage(pageIndex);
                stripper.setEndPage(pageIndex);
                String pageText = stripper.getText(pdf);
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }

                Document pageDocument = Document.from(pageText);
                List<TextSegment> pageChunks = splitter.split(pageDocument);

                for (int chunkIndex = 0; chunkIndex < pageChunks.size(); chunkIndex++) {
                    TextSegment chunk = pageChunks.get(chunkIndex);
                    Metadata metadata = new Metadata()
                            .put("sourceFileName", originalName)
                            .put("sourceFileId", sourceFileId)
                            .put("pageNumber", pageIndex)
                            .put("chunkIndex", chunkIndex)
                            .put("ingestedAt", ingestedAt.toString());
                    allChunks.add(TextSegment.from(chunk.text(), metadata));
                }
            }
        } catch (IOException e) {
            log.error("Failed to parse PDF '{}' ({})", originalName, sourceFileId, e);
            throw new UpstreamServiceException("Could not read the uploaded PDF. It may be corrupted or encrypted.", e);
        }

        return allChunks;
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
}
