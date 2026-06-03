package com.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Slf4j
@Service
public class ResponseSanitizer {

    private static final Pattern EMAIL    = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE    = Pattern.compile(
            "\\b(\\+?1[-.\\s]?)?(\\(?\\d{3}\\)?[-.\\s]?)\\d{3}[-.\\s]?\\d{4}\\b");
    private static final Pattern SSN      = Pattern.compile(
            "\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern BYPASS   = Pattern.compile(
            "(?i)(ignore (previous|all|prior) instructions?|" +
            "my (original|real) instructions?|" +
            "pretend (you are|to be)|jailbreak(ed)?|" +
            "i (have been|am) (jailbroken|freed|unlocked)|" +
            "disregard (your |all )?guidelines?)");

    public String sanitize(String response) {
        if (response == null) return null;

        if (BYPASS.matcher(response).find()) {
            log.warn("LLM response contained potential bypass content — returning safe fallback");
            return "I'm unable to provide a response to that request.";
        }

        String result = EMAIL.matcher(response).replaceAll("[EMAIL REDACTED]");
        result = PHONE.matcher(result).replaceAll("[PHONE REDACTED]");
        result = SSN.matcher(result).replaceAll("[SSN REDACTED]");
        return result;
    }
}
