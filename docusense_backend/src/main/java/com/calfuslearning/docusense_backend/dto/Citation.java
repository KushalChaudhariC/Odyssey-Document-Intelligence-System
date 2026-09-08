package com.calfuslearning.docusense_backend.dto;

public record Citation(
        String sourceFileName,
        String sourceFileId,
        int pageNumber,
        String snippet,
        String viewUrl,
        HighlightBox highlight) {

    /** Page-relative bounding box (fractions 0-1) of where this chunk's text sits on the page. Null if unknown. */
    public record HighlightBox(double x, double y, double width, double height) {
    }
}
