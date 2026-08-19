package com.calfuslearning.docusense_backend.exception;

import org.springframework.http.HttpStatus;

/** Thrown when the client uploads a file that is not a PDF. */
public class UnsupportedFileTypeException extends ApiException {

    public UnsupportedFileTypeException(String message) {
        super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message);
    }
}
