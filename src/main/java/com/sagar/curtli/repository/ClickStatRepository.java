package com.sagar.curtli.repository;

import com.sagar.curtli.domain.ClickStat;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface ClickStatRepository extends JpaRepository<ClickStat, Long> {

    // This method handles the "Upsert" logic.
    // If a row for this link and hour already exists, it adds the new clicks.
    // If it doesn't exist, it inserts a new row. (Native query used for Postgres ON CONFLICT support)
    @Modifying
    @Query(value = """
        INSERT INTO click_stats (link_id, bucket_hour, click_count)
        VALUES (:linkId, :bucketHour, :clickCount)
        ON CONFLICT (link_id, bucket_hour)
        DO UPDATE SET click_count = click_stats.click_count + EXCLUDED.click_count
        """, nativeQuery = true)
    void upsertClickCount(
            @Param("linkId") Long linkId,
            @Param("bucketHour") OffsetDateTime bucketHour,
            @Param("clickCount") long clickCount
    );

    /**
     * Most recent hour buckets for a link, newest first.
     * Pageable controls how many we return — service caps at 24 (last 24 hours).
     */
    @Query("SELECT cs FROM ClickStat cs WHERE cs.link.id = :linkId ORDER BY cs.bucketHour DESC")
    List<ClickStat> findRecentByLinkId(@Param("linkId") Long linkId, Pageable pageable);
}