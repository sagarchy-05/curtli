package com.sagar.curtli.dto;

public record ShortenResponse(
        Long id,
        String shortCode,
        String shortUrl,
        String longUrl
) {}