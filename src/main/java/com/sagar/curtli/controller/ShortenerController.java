package com.sagar.curtli.controller;

import com.sagar.curtli.dto.BulkShortenResponse;
import com.sagar.curtli.dto.LinkStatsResponse;
import com.sagar.curtli.dto.ShortenRequest;
import com.sagar.curtli.service.LinkStatsService;
import com.sagar.curtli.service.ShortenerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ShortenerController {

    private final ShortenerService service;
    private final LinkStatsService statsService;

    /**
     * Always accepts an array (1..BULK_BATCH_SIZE URLs). The frontend sends a
     * single-element array when shortening just one link. Returns a
     * BulkShortenResponse with separate successful/failed lists so one bad
     * URL never sinks the whole batch.
     *
     * 400 only when EVERY URL in the batch failed; partial success is 200.
     */
    @PostMapping("/shorten")
    public ResponseEntity<BulkShortenResponse> shorten(@Valid @RequestBody List<ShortenRequest> reqs) {
        BulkShortenResponse response = service.bulkShorten(reqs);

        if (response.successful().isEmpty() && !response.failed().isEmpty()) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Hourly click breakdown for a single link. Looked up by numeric id so the
     * public shortCode isn't a stats key — only the creator's browser has the
     * id (stored in localStorage when the link was created).
     */
    @GetMapping("/links/{id}/stats")
    public ResponseEntity<LinkStatsResponse> stats(@PathVariable Long id) {
        return ResponseEntity.ok(statsService.getStats(id));
    }
}
