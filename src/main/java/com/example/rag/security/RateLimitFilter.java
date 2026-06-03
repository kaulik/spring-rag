package com.example.rag.security;

import com.example.rag.config.GuardrailProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final GuardrailProperties guardrailProperties;

    public RateLimitFilter(GuardrailProperties guardrailProperties) {
        this.guardrailProperties = guardrailProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (!request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, this::newBucket);

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for key={}", key);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please retry after a minute.\"}");
        }
    }

    private String resolveKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        return (apiKey != null && !apiKey.isBlank()) ? "key:" + apiKey : "ip:" + request.getRemoteAddr();
    }

    private Bucket newBucket(String key) {
        int rpm = guardrailProperties.getRateLimit().getRequestsPerMinute();
        Bandwidth limit = Bandwidth.classic(rpm, Refill.greedy(rpm, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
