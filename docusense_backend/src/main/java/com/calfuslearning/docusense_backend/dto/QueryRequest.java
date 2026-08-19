package com.calfuslearning.docusense_backend.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(@NotBlank(message = "question must not be blank") String question) {
}
