package com.calfuslearning.docusense_backend.dto;

public record Citation(String sourceFileName, String sourceFileId, int pageNumber, String snippet, String viewUrl) {
}
