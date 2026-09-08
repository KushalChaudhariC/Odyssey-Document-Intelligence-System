package com.calfuslearning.docusense_backend.service.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** LangChain4j declarative AI service for the RAG question-answering call. */
public interface DocumentQaAssistant {

    @SystemMessage("""
            You are DocuSense, an internal document assistant. Answer the user's question
            using ONLY the information in the provided document excerpts below. Do not use
            any outside knowledge. If the excerpts do not contain enough information to
            answer the question, say so clearly instead of guessing.

            For every factual claim you make, note which excerpt it came from.

            Document excerpts:
            {{excerpts}}
            """)
    String answer(@UserMessage String question, @V("excerpts") String excerpts);

    @UserMessage("""
            Rephrase the following question using different wording, while keeping the same
            meaning. Respond with only the rephrased question, nothing else.

            Question: {{question}}
            """)
    String rephrase(@V("question") String question);
}
