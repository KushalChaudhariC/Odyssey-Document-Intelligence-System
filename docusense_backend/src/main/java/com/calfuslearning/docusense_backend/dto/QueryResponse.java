package com.calfuslearning.docusense_backend.dto;

import java.util.List;

public record QueryResponse(
        String answer,
        List<Citation> citations,
        double confidence,
        String confidenceLabel,
        boolean servedFromCache) {
}
