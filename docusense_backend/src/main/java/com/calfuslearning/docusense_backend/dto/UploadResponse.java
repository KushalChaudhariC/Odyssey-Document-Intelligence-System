package com.calfuslearning.docusense_backend.dto;

public record UploadResponse(String sourceFileId, String fileName, int chunkCount) {
}
