package com.calfuslearning.docusense_backend.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.calfuslearning.docusense_backend.dto.DocumentSummary;
import com.calfuslearning.docusense_backend.dto.UploadResponse;
import com.calfuslearning.docusense_backend.exception.DocumentNotFoundException;
import com.calfuslearning.docusense_backend.service.DocumentCatalogService;
import com.calfuslearning.docusense_backend.service.IngestionService;
import com.calfuslearning.docusense_backend.service.PdfStorageService;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final IngestionService ingestionService;
    private final DocumentCatalogService catalogService;
    private final PdfStorageService pdfStorageService;

    public DocumentController(
            IngestionService ingestionService, DocumentCatalogService catalogService, PdfStorageService pdfStorageService) {
        this.ingestionService = ingestionService;
        this.catalogService = catalogService;
        this.pdfStorageService = pdfStorageService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        log.info("Received upload request: {}", file != null ? file.getOriginalFilename() : "null");
        UploadResponse response = ingestionService.ingest(file);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<DocumentSummary>> list() {
        return ResponseEntity.ok(catalogService.listAll());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetAll() {
        log.warn("Received request to reset the entire corpus (documents + vector stores + cache)");
        ingestionService.resetAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> getDocument(@PathVariable("id") String id) {
        if (!catalogService.contains(id)) {
            throw new DocumentNotFoundException(id);
        }
        Resource resource = pdfStorageService.loadAsResource(id);
        if (resource == null) {
            throw new DocumentNotFoundException(id);
        }
        log.info("Streaming stored PDF for sourceFileId={}", id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + id + ".pdf\"")
                .body(resource);
    }
}
