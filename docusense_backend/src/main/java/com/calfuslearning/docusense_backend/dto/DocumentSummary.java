package com.calfuslearning.docusense_backend.dto;

import java.time.Instant;

public record DocumentSummary(String sourceFileId, String fileName, int chunkCount, Instant ingestedAt) {
}
