package com.sagar.curtli.service;

/**
 * Three-state outcome of a rate-limit check.
 *
 * <ul>
 *   <li>{@link #ALLOWED} — under the cap, request proceeds.</li>
 *   <li>{@link #DENIED} — over the cap, caller returns 429.</li>
 *   <li>{@link #UNAVAILABLE} — Redis errored (timeout, OOM under noeviction,
 *       connection refused). Caller should return 503 with Retry-After so
 *       clients back off rather than retrying immediately and amplifying the
 *       underlying problem.</li>
 * </ul>
 *
 * Bypassing the limiter on Redis failure (returning ALLOWED instead) would
 * let unrate-limited traffic through during the exact incident the limiter
 * exists to handle — so UNAVAILABLE is a fail-closed signal, not fail-open.
 */
public enum RateLimitResult {
    ALLOWED, DENIED, UNAVAILABLE
}
