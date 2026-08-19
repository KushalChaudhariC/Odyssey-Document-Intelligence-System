package com.calfuslearning.docusense_backend.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a downstream dependency (OpenAI, Weaviate, disk I/O) fails and the request cannot be completed. */
public class UpstreamServiceException extends ApiException {

    public UpstreamServiceException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message);
        initCause(cause);
    }
}
