package com.sagar.curtli.service;

import com.sagar.curtli.domain.ClickStat;
import com.sagar.curtli.domain.Link;
import com.sagar.curtli.dto.HourBucket;
import com.sagar.curtli.dto.LinkStatsResponse;
import com.sagar.curtli.exception.LinkNotFoundException;
import com.sagar.curtli.repository.ClickStatRepository;
import com.sagar.curtli.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Reads click stats for a single link. Backs GET /api/links/{id}/stats.
 *
 * Returns at most 24 hourly buckets — the modal renders a last-24h chart.
 * No caching: counts change every consumer flush (~1s), and a SELECT on
 * a (link_id, bucket_hour) index against ~24 rows is already sub-ms.
 */
@Service
@RequiredArgsConstructor
public class LinkStatsService {

    private static final int LAST_24_HOURS = 24;

    private final LinkRepository linkRepository;
    private final ClickStatRepository clickStatRepository;

    @Transactional(readOnly = true)
    public LinkStatsResponse getStats(Long id) {
        Link link = linkRepository.findById(id)
                .orElseThrow(() -> new LinkNotFoundException(String.valueOf(id)));

        OffsetDateTime since = OffsetDateTime.now()
                .truncatedTo(ChronoUnit.HOURS)
                .minusHours(LAST_24_HOURS - 1);

        List<ClickStat> stats = clickStatRepository.findRecentByLinkIdSince(id, since);

        List<HourBucket> hourly = stats.stream()
                .map(s -> new HourBucket(s.getBucketHour(), s.getClickCount()))
                .toList();

        return new LinkStatsResponse(
                link.getId(),
                link.getShortCode(),
                link.getLongUrl(),
                link.getClickCount(),
                hourly
        );
    }
}
