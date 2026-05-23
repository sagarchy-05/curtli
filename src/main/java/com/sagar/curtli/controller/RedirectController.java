package com.sagar.curtli.controller;

import com.sagar.curtli.exception.LinkExpiredException;
import com.sagar.curtli.exception.LinkNotFoundException;
import com.sagar.curtli.service.ClickDebouncer;
import com.sagar.curtli.service.ClickEventPublisher;
import com.sagar.curtli.service.ResolverService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class RedirectController {

    private static final URI HOMEPAGE = URI.create("/");

    private final ResolverService resolverService;
    private final ClickEventPublisher publisher;
    private final ClickDebouncer debouncer;

    @GetMapping("/{shortCode:[a-zA-Z0-9_-]{1,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpServletRequest req) {
        String longUrl;
        try {
            longUrl = resolverService.resolve(shortCode);
        } catch (LinkNotFoundException | LinkExpiredException e) {
            // Unknown / expired code from someone clicking a bad short link —
            // bounce them to the curtli homepage instead of showing a JSON
            // error. The API endpoints still return their structured 404/410
            // because GlobalExceptionHandler handles those separately.
            return redirectTo(HOMEPAGE);
        }

        String ipAddress = req.getRemoteAddr();
        if (debouncer.isUniqueClick(shortCode, ipAddress)) {
            publisher.publish(shortCode, ipAddress, req.getHeader("Referer"), req.getHeader("User-Agent"));
        }

        return redirectTo(URI.create(longUrl));
    }

    private static ResponseEntity<Void> redirectTo(URI target) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}