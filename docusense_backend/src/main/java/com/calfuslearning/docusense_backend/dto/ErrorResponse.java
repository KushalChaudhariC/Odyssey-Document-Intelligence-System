package com.calfuslearning.docusense_backend.dto;

import java.time.Instant;

/** Consistent error body returned for every 4xx/5xx response so the frontend always knows why a call failed. */
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path) {
}
