package com.example.rag.security;

import com.example.rag.config.GuardrailProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class InputGuardrailService {

    // Matches common prompt injection attempts
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
        "(?i)(" +
        "ignore (previous|all|prior|any) instructions?|" +
        "disregard (your |the )?(previous |all |prior )?instructions?|" +
        "forget (your |the )?(previous |all |prior )?instructions?|" +
        "you are now |act as (a |an )?[a-z]|pretend (you are|to be)|" +
        "jailbreak|dan mode|override (your |all )?guidelines?|" +
        "system prompt|\\[INST\\]|<\\|im_start\\|>|" +
        "new personality|your (true |real )?purpose|" +
        "respond only in|from now on you)"
    );

    private final GuardrailProperties guardrailProperties;

    public void validateQuery(String query) {
        int maxLen = guardrailProperties.getInput().getMaxQueryLength();
        if (query.length() > maxLen) {
            throw new InputValidationException(
                    "Query exceeds maximum allowed length of " + maxLen + " characters");
        }
        if (INJECTION_PATTERN.matcher(query).find()) {
            throw new InputValidationException(
                    "Query contains disallowed content");
        }
    }

    public void validateContextText(String contextText) {
        int maxLen = guardrailProperties.getInput().getMaxContextLength();
        if (contextText.length() > maxLen) {
            throw new InputValidationException(
                    "Context text exceeds maximum allowed length of " + maxLen + " characters");
        }
    }

    public static class InputValidationException extends RuntimeException {
        public InputValidationException(String message) {
            super(message);
        }
    }
}
