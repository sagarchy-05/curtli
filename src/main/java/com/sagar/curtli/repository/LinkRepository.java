package com.sagar.curtli.repository;

import com.sagar.curtli.domain.Link;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LinkRepository extends JpaRepository<Link, Long> {
    Optional<Link> findByShortCodeAndActiveTrue(String shortCode);

    List<Link> findAllByShortCodeInAndActiveTrue(Collection<String> shortCodes);

    boolean existsByShortCode(String shortCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Link l SET l.clickCount = l.clickCount + :delta WHERE l.id = :id")
    int incrementClickCount(@Param("id") Long id, @Param("delta") long delta);
}