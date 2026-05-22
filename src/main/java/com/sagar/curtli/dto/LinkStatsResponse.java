package com.sagar.curtli.dto;

import java.util.List;

public record LinkStatsResponse(
        Long id,
        String shortCode,
        String longUrl,
        long totalClicks,
        List<HourBucket> hourly
) {}
