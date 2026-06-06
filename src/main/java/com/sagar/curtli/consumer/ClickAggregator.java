package com.sagar.curtli.consumer;

import com.sagar.curtli.domain.Link;
import com.sagar.curtli.repository.ClickStatRepository;
import com.sagar.curtli.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClickAggregator {

    private final LinkRepository linkRepository;
    private final ClickStatRepository clickStatRepository;

    @Transactional
    public void flush(Map<String, Long> codeToCount) {
        if (codeToCount == null || codeToCount.isEmpty()) {
            return;
        }

        OffsetDateTime currentHourBucket = OffsetDateTime.now().truncatedTo(ChronoUnit.HOURS);

        // One SELECT for the whole batch — avoids the N round-trips a per-code
        // findByShortCode loop would issue.
        List<Link> links = linkRepository.findAllByShortCodeInAndActiveTrue(codeToCount.keySet());
        Map<String, Long> codeToId = links.stream()
                .collect(Collectors.toMap(Link::getShortCode, Link::getId));

        for (Map.Entry<String, Long> entry : codeToCount.entrySet()) {
            String shortCode = entry.getKey();
            Long newClicks = entry.getValue();
            Long linkId = codeToId.get(shortCode);

            if (linkId == null) {
                log.warn("Aggregator: Link not found or inactive for code: {}", shortCode);
                continue;
            }

            try {
                linkRepository.incrementClickCount(linkId, newClicks);
                clickStatRepository.upsertClickCount(linkId, currentHourBucket, newClicks);
            } catch (Exception e) {
                log.error("Failed to aggregate clicks for code {}: {}", shortCode, e.getMessage());
            }
        }
    }
}