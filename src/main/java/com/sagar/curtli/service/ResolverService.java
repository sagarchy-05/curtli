package com.sagar.curtli.service;

import com.sagar.curtli.domain.Link;
import com.sagar.curtli.exception.LinkExpiredException;
import com.sagar.curtli.exception.LinkNotFoundException;
import com.sagar.curtli.repository.LinkRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResolverService {
    private final LinkRepository repository;
    private final StringRedisTemplate redis;

    @Value("${curtli.cache-ttl-seconds}") private long cacheTtl;
    @Value("${curtli.local-cache-ttl-seconds:30}") private long localCacheTtl;
    
    private Cache<String, String> localCache;

    @PostConstruct
    public void init() {
        localCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(localCacheTtl))
                .maximumSize(10_000)
                .build();
    }

    @Transactional(readOnly = true)
    public String resolve(String shortCode) {
        // Try L1 local cache first
        String localCached = localCache.getIfPresent(cacheKey(shortCode));
        if (localCached != null) {
            return localCached;
        }

        // Try Redis cache
        String redisCached = null;
        try {
            redisCached = redis.opsForValue().get(cacheKey(shortCode));
        } catch (Exception e) {
            log.warn("Redis read failed for code={}: {}", shortCode, e.getMessage());
        }

        if (redisCached != null) {
            localCache.put(cacheKey(shortCode), redisCached);
            return redisCached;
        }

        // Fallback to DB
        Link link = repository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new LinkNotFoundException(shortCode));

        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new LinkExpiredException(shortCode);
        }

        cachePut(shortCode, link.getLongUrl(), link.getExpiresAt());
        localCache.put(cacheKey(shortCode), link.getLongUrl());
        return link.getLongUrl();
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