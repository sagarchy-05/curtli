package com.sagar.curtli.dto;

import java.time.OffsetDateTime;

public record HourBucket(
        OffsetDateTime hour,
        long clicks
) {}
