package com.sagar.curtli.consumer;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClickEventConsumer {
    private final StringRedisTemplate redis;
    private final ClickAggregator aggregator;

    @Value("${curtli.click-stream.name}") private String streamName;
    @Value("${curtli.click-stream.consumer-group}") private String group;
    @Value("${curtli.click-stream.consumer-name}") private String consumer;
    @Value("${curtli.click-stream.batch-size:200}") private int batchSize;
    @Value("${curtli.click-stream.block-millis:500}") private long blockMillis;

    @PostConstruct
    public void initGroup() {
        try {
            // 1. Emulate MKSTREAM by creating the stream with a dummy record
            //    if it doesn't exist (Spring Data Redis high-level API doesn't
            //    expose the MKSTREAM flag directly).
            if (!Boolean.TRUE.equals(redis.hasKey(streamName))) {
                redis.opsForStream().add(streamName, Map.of("init", "1"));
                log.info("Created new Redis stream: {}", streamName);
            }

            // 2. Create the consumer group.
            redis.opsForStream().createGroup(streamName, ReadOffset.from("0"), group);
            log.info("Successfully created consumer group: {}", group);

        } catch (Exception e) {
            if (isBusyGroup(e)) {
                // Expected on every boot after the first — the group already
                // exists from a prior run. Not an error.
                log.info("Redis consumer group '{}' already exists. Skipping.", group);
            } else {
                // Anything else IS a real problem worth shouting about.
                log.error("CRITICAL: Failed to initialize Redis stream/group", e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${curtli.click-stream.poll-delay:1000}")
    public void consume() {
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(group, consumer),
                    StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(blockMillis)),
                    StreamOffset.create(streamName, ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) return;

            Map<String, Long> codeToCount = new HashMap<>();
            for (MapRecord<String, Object, Object> r : records) {
                Object raw = r.getValue().get("shortCode");
                if (raw == null) continue;          // skip the init placeholder & malformed entries
                codeToCount.merge(raw.toString(), 1L, Long::sum);
            }

            if (!codeToCount.isEmpty()) {
                aggregator.flush(codeToCount);
            }

            // Acknowledge every record we read (including the init placeholder
            // and any we skipped above) in a single XACK call so we make one
            // Redis round-trip per batch rather than N.
            RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
            redis.opsForStream().acknowledge(streamName, group, ids);
        } catch (Exception e) {
            // A transient Redis or DB hiccup (timeout, brief disconnect, etc.)
            // would otherwise spam ERROR + full stack trace via Spring's
            // scheduler error handler on every tick. Log a one-line WARN
            // with the root cause and move on — the next tick retries.
            Throwable root = rootCause(e);
            log.warn("Consumer poll failed: {} — {} (will retry next tick)",
                    root.getClass().getSimpleName(), root.getMessage());
        }
    }

    /**
     * BUSYGROUP arrives as the message on Lettuce's RedisBusyException, which
     * Spring wraps in RedisSystemException("Error in execution"). Walk the
     * cause chain so we don't classify a normal "group already exists" boot
     * as a CRITICAL failure.
     */
    private static boolean isBusyGroup(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) return true;
        }
        return false;
    }

    /** Walks down to the deepest cause, defending against pathological cycles. */
    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}
