package com.sagar.curtli.consumer;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
            // Use MKSTREAM so this also creates the stream if it doesn't exist yet
            // (fresh ElastiCache, fresh local Redis). The high-level opsForStream()
            // .createGroup() doesn't expose this flag, so we drop to the connection
            // callback.
            redis.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            streamName.getBytes(StandardCharsets.UTF_8),
                            group,
                            ReadOffset.from("0"),
                            true
                    )
            );
        } catch (Exception ignored) {
            // BUSYGROUP if the group already exists on subsequent boots. Safe to ignore.
        }
    }

    @Scheduled(fixedDelayString = "${curtli.click-stream.poll-delay:1000}")
    public void consume() {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(blockMillis)),
                StreamOffset.create(streamName, ReadOffset.lastConsumed())
        );

        if (records == null || records.isEmpty()) return;

        Map<String, Long> codeToCount = new HashMap<>();
        for (MapRecord<String, Object, Object> r : records) {
            String code = String.valueOf(r.getValue().get("shortCode"));
            codeToCount.merge(code, 1L, Long::sum);
        }

        aggregator.flush(codeToCount);

        // Acknowledge
        for (MapRecord<String, Object, Object> r : records) {
            redis.opsForStream().acknowledge(streamName, group, r.getId());
        }
    }
}