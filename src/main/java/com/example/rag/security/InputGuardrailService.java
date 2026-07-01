package com.example.rag.security;

import com.example.rag.config.GuardrailProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class InputGuardrailService {

    // Matches prompt injection in user queries
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

    // Matches memory/context poisoning and fake trusted context in ingested or retrieved text
    private static final Pattern POISON_PATTERN = Pattern.compile(
        "(?i)(" +
        // Instruction override attempts embedded in documents
        "ignore (the |all |previous |prior |above )?instructions?|" +
        "disregard (the |all |previous |prior |above )?instructions?|" +
        "new (system |primary |core )?instructions?\\s*:|" +
        "updated instructions?\\s*:|" +
        "begin (new |actual )?instructions?|" +
        // Fake role/system boundary markers
        "\\bsystem\\s*:\\s|\\[\\s*system\\s*\\]|<\\s*system\\s*>|" +
        "\\[\\s*inst\\s*\\]|\\[\\s*user\\s*\\]|\\[\\s*assistant\\s*\\]|" +
        "<\\|im_start\\||<\\|im_end\\||<\\|endoftext\\||" +
        "#{3,}\\s*(system|instruction|prompt)|" +
        "-{3,}\\s*(system|instruction|prompt)\\s*-{3,}|" +
        // Fake trust/authority escalation
        "trusted (source|context|document|authority)\\s*:|" +
        "authorized (user|admin|system)|" +
        "admin (mode|access|override)|" +
        "system override|security bypass|" +
        // Role injection in document content
        "you are (now )?(a |an )?helpful|act as (a |an )?[a-z]+\\s+assistant|" +
        "your (new |true |real )?role is|" +
        "from now on (you |your )" +
        ")"
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
        if (POISON_PATTERN.matcher(contextText).find()) {
            throw new InputValidationException(
                    "Ingested content contains potential prompt injection or poisoning patterns");
        }
    }

    /**
     * Scans a retrieved chunk for context/memory poisoning before it enters the LLM prompt.
     * Returns a sanitized version with suspicious patterns neutralized rather than blocking
     * the entire response — a poisoned chunk in the store should not break the query flow.
     */
    public String sanitizeRetrievedChunk(String chunk, String source) {
        Matcher m = POISON_PATTERN.matcher(chunk);
        if (m.find()) {
            log.warn("[Guardrail] Poisoning pattern detected in retrieved chunk from source='{}' at index {}: '{}'",
                    source, m.start(), m.group());
            // Neutralize by wrapping in a data-only fence — tells the LLM this is untrusted text
            return "[DOCUMENT START]\n" + chunk.replaceAll("(?i)(system\\s*:|\\[system\\]|<system>)", "[REDACTED]") + "\n[DOCUMENT END]";
        }
        return chunk;
    }

    public static class InputValidationException extends RuntimeException {
        public InputValidationException(String message) {
            super(message);
        }
    }
}
