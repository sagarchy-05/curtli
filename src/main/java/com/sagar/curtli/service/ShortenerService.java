package com.sagar.curtli.service;

import com.sagar.curtli.domain.Link;
import com.sagar.curtli.dto.BulkError;
import com.sagar.curtli.dto.BulkShortenResponse;
import com.sagar.curtli.dto.ShortenRequest;
import com.sagar.curtli.dto.ShortenResponse;
import com.sagar.curtli.encoding.Base62;
import com.sagar.curtli.repository.LinkRepository;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShortenerService {
    private final LinkRepository repository;
    private final StringRedisTemplate redis;

    @Value("${curtli.base-url}") private String baseUrl;
    @Value("${curtli.cache-ttl-seconds}") private long cacheTtl;
    @Value("${curtli.bulk-batch-size}") private int maxBulkSize;

    /**
     * Intentionally NOT @Transactional: each repository.save() runs in its
     * own transaction (managed by SimpleJpaRepository), which is critical for
     * the random-code retry loop below — a constraint violation in one
     * attempt must not poison subsequent attempts. Also avoids populating the
     * Redis cache before the DB transaction commits.
     */
    public ShortenResponse shorten(ShortenRequest req) {
        validate(req.longUrl());

        // 1. Custom Alias Flow (The "Ask Forgiveness" Pattern)
        if (req.customAlias() != null && !req.customAlias().isBlank()) {
            String alias = req.customAlias().trim();
            if (!alias.matches("[a-zA-Z0-9_-]{3,16}")) {
                throw new IllegalArgumentException("Invalid alias format");
            }

            Link link = Link.builder()
                    .shortCode(alias)
                    .longUrl(req.longUrl())
                    .custom(true)
                    .expiresAt(computeExpiry(req.expiresInDays()))
                    .build();

            try {
                link = repository.save(link);
            } catch (DataIntegrityViolationException e) {
                throw new IllegalArgumentException("Alias already taken");
            }

            cachePut(alias, link.getLongUrl(), link.getExpiresAt());
            return new ShortenResponse(link.getId(), alias, baseUrl + "/" + alias, link.getLongUrl());
        }

        // 2. Generated Code Flow (With Collision Retry Logic)
        Link link = null;
        int attempts = 0;
        while (link == null && attempts < 5) {
            String generatedCode = Base62.randomCode(7);
            Link tempLink = Link.builder()
                    .shortCode(generatedCode)
                    .longUrl(req.longUrl())
                    .custom(false)
                    .expiresAt(computeExpiry(req.expiresInDays()))
                    .build();
            try {
                link = repository.save(tempLink);
            } catch (DataIntegrityViolationException e) {
                attempts++;
                log.warn("Collision detected for generated code: {}, retrying...", generatedCode);
                if (attempts >= 5) {
                    throw new IllegalStateException("Failed to generate unique short code after 5 attempts");
                }
            }
        }

        cachePut(link.getShortCode(), link.getLongUrl(), link.getExpiresAt());
        return new ShortenResponse(link.getId(), link.getShortCode(), baseUrl + "/" + link.getShortCode(), link.getLongUrl());
    }

    // 3. Bulk Shorten (now the only public entry point — handles 1..maxBulkSize URLs)
    public BulkShortenResponse bulkShorten(List<ShortenRequest> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            throw new IllegalArgumentException("At least one URL is required");
        }
        if (reqs.size() > maxBulkSize) {
            throw new IllegalArgumentException("Max " + maxBulkSize + " URLs per request");
        }

        List<ShortenResponse> successful = new ArrayList<>();
        List<BulkError> failed = new ArrayList<>();

        for (ShortenRequest req : reqs) {
            try {
                // Try to process the individual link
                ShortenResponse response = this.shorten(req);
                successful.add(response);
            } catch (IllegalArgumentException e) {
                // If it fails (e.g., alias taken, bad URL format), catch it!
                failed.add(new BulkError(
                        req.longUrl(),
                        req.customAlias(),
                        e.getMessage() // "Alias already taken", "URL too long", etc.
                ));
            } catch (Exception e) {
                // Catch any unexpected database hiccups for this specific link
                log.error("Unexpected error shortening URL: {}", req.longUrl(), e);
                failed.add(new BulkError(req.longUrl(), req.customAlias(), "Internal processing error"));
            }
        }

        return new BulkShortenResponse(successful, failed);
    }

    private void validate(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("URL required");
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw new IllegalArgumentException("URL must use http or https");
        if (url.length() > 2048) throw new IllegalArgumentException("URL too long");
    }

    /**
     * Bean Validation (@Min/@Max on the DTO) covers the /api/shorten endpoint.
     * For the bulk path, list elements are NOT cascade-validated (intentionally,
     * so bulk can keep its per-row partial-success semantics), so we re-check
     * the same rules here. The throw becomes an IllegalArgumentException which
     * bulkShorten() catches and reports per row.
     */
    private OffsetDateTime computeExpiry(Long expiresInDays) {
        if (expiresInDays == null) return null;
        if (expiresInDays < 1) {
            throw new IllegalArgumentException("expiresInDays must be at least 1");
        }
        if (expiresInDays > 3650) {
            throw new IllegalArgumentException("expiresInDays must be at most 3650 (10 years)");
        }
        return OffsetDateTime.now().plusDays(expiresInDays);
    }

    private String cacheKey(String code) { return "link:" + code; }

    /**
     * Cache the long URL with a TTL bounded by the link's own expiry, so a
     * cached entry can never outlive its link.
     */
    private void cachePut(String code, String longUrl, OffsetDateTime expiresAt) {
        long ttl = cacheTtl;
        if (expiresAt != null) {
            long secsLeft = Duration.between(OffsetDateTime.now(), expiresAt).getSeconds();
            if (secsLeft < ttl) ttl = Math.max(secsLeft, 1);
        }
        try {
            redis.opsForValue().set(cacheKey(code), longUrl, Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("Cache write failed for code={}: {}", code, e.getMessage());
        }
    }
}