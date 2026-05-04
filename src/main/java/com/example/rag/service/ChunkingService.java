package com.example.rag.service;

import com.example.rag.config.RagProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits raw text into overlapping sentence-window chunks.
 *
 * Strategy:
 *   - Split text into sentences on [.!?] + whitespace boundaries.
 *   - Slide a window of N sentences (default 3).
 *   - Prepend the last ~20 tokens from the previous chunk to each new chunk
 *     so context is preserved across boundaries.
 */
@Component
@RequiredArgsConstructor
public class ChunkingService {

    private final RagProperties props;

    /** Matches sentence-ending punctuation followed by one or more whitespace characters. */
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[.!?])\\s+");

    @Data
    @AllArgsConstructor
    public static class Chunk {
        private String text;
        private String source;
        private String chunkId;
    }

    public List<Chunk> chunkText(String rawText, String source) {
        int sentencesPerChunk = props.getChunking().getSentencesPerChunk();
        int tokenOverlap      = props.getChunking().getTokenOverlap();

        // Normalise whitespace
        String normalised = rawText == null ? "" : rawText.replaceAll("\\s+", " ").trim();
        if (normalised.isEmpty()) {
            return List.of();
        }

        String[] sentences = SENTENCE_BOUNDARY.split(normalised);
        List<Chunk> chunks = new ArrayList<>();
        List<String> prevTailTokens = new ArrayList<>();

        int sentIdx  = 0;
        int chunkIdx = 0;

        while (sentIdx < sentences.length) {
            // Build the window of sentences for this chunk
            List<String> window = new ArrayList<>();
            for (int i = 0; i < sentencesPerChunk && (sentIdx + i) < sentences.length; i++) {
                String s = sentences[sentIdx + i].trim();
                if (!s.isEmpty()) {
                    window.add(s);
                }
            }
            sentIdx += sentencesPerChunk;

            // Prepend tail of previous chunk for overlap
            StringBuilder combined = new StringBuilder();
            if (!prevTailTokens.isEmpty()) {
                combined.append(String.join(" ", prevTailTokens)).append(" ");
            }
            combined.append(String.join(" ", window));

            String text = combined.toString().trim();
            if (text.isEmpty()) {
                continue;
            }

            // Update overlap buffer for the next chunk
            String[] tokens = text.split("\\s+");
            prevTailTokens.clear();
            if (tokenOverlap > 0 && tokens.length > 0) {
                int start = Math.max(0, tokens.length - tokenOverlap);
                prevTailTokens.addAll(Arrays.asList(tokens).subList(start, tokens.length));
            }

            String chunkId = source + "-chunk-" + chunkIdx++;
            chunks.add(new Chunk(text, source, chunkId));
        }

        return chunks;
    }
}
