package com.calfuslearning.docusense_backend.service;

import com.calfuslearning.docusense_backend.exception.UpstreamServiceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Reads and writes the original PDF files on local disk, keyed by sourceFileId. */
@Service
public class PdfStorageService {

    private static final Logger log = LoggerFactory.getLogger(PdfStorageService.class);

    private final Path uploadDir;

    public PdfStorageService(@Value("${docusense.storage.upload-dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir);
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create upload directory: " + this.uploadDir, e);
        }
    }

    public void save(String sourceFileId, MultipartFile file) {
        Path target = pdfPath(sourceFileId);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            log.error("Failed to save uploaded PDF {} to disk", sourceFileId, e);
            throw new UpstreamServiceException("Could not save the uploaded file to disk storage.", e);
        }
    }

    public Resource loadAsResource(String sourceFileId) {
        Path path = pdfPath(sourceFileId);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return new UrlResource(path.toUri());
        } catch (Exception e) {
            log.error("Failed to load stored PDF {} from disk", sourceFileId, e);
            throw new UpstreamServiceException("Could not read the stored file from disk.", e);
        }
    }

    public boolean exists(String sourceFileId) {
        return Files.exists(pdfPath(sourceFileId));
    }

    public void delete(String sourceFileId) {
        try {
            Files.deleteIfExists(pdfPath(sourceFileId));
        } catch (IOException e) {
            log.warn("Failed to delete stored PDF {}", sourceFileId, e);
        }
    }

    /** Deletes every stored PDF on disk. Used by the full reset endpoint. */
    public void deleteAll() {
        try (var stream = Files.newDirectoryStream(uploadDir, "*.pdf")) {
            int count = 0;
            for (Path pdf : stream) {
                Files.deleteIfExists(pdf);
                count++;
            }
            log.info("Deleted {} stored PDF(s) from {}", count, uploadDir);
        } catch (IOException e) {
            log.warn("Failed to delete stored PDFs from {}", uploadDir, e);
        }
    }

    private Path pdfPath(String sourceFileId) {
        return uploadDir.resolve(sourceFileId + ".pdf");
    }
}
