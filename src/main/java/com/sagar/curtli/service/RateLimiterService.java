package com.sagar.curtli.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RateLimiterService {
    private final StringRedisTemplate redis;
    private DefaultRedisScript<List> script;

    @Value("${curtli.rate-limit.anonymous-per-minute}") private int anonRate;

    @PostConstruct
    public void init() {
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);
    }

    /**
     * One token per request. With the new unified /api/shorten endpoint a
     * single request can carry up to BULK_BATCH_SIZE URLs, but they still
     * cost one token — keeps the limiter simple and the 10/min default
     * generous enough for any realistic interactive use.
     */
    public boolean tryAcquire(String identityKey) {
        return executeLua("rl:" + identityKey, anonRate, anonRate / 60.0);
    }

    private boolean executeLua(String key, int maxTokens, double refillPerSec) {
        List<?> result = redis.execute(script,
                List.of(key),
                String.valueOf(maxTokens),
                String.valueOf(refillPerSec),
                String.valueOf(Instant.now().getEpochSecond()),
                "1");
        return result != null && Long.parseLong(result.getFirst().toString()) == 1L;
    }
}