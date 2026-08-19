package com.calfuslearning.docusense_backend.controller;

import com.calfuslearning.docusense_backend.dto.QueryRequest;
import com.calfuslearning.docusense_backend.dto.QueryResponse;
import com.calfuslearning.docusense_backend.service.QueryService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        log.info("Received question ({} chars)", request.question().length());
        return ResponseEntity.ok(queryService.answer(request.question()));
    }
}
