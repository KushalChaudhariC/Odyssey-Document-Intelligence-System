package com.calfuslearning.docusense_backend.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a requested document id does not exist in the catalog. */
public class DocumentNotFoundException extends ApiException {

    public DocumentNotFoundException(String sourceFileId) {
        super(HttpStatus.NOT_FOUND, "No document found with id '" + sourceFileId + "'.");
    }
}
