package com.hack.segmentrec.controller;

import com.hack.segmentrec.model.ChatRecommendRequest;
import com.hack.segmentrec.model.ChatRecommendResponse;
import com.hack.segmentrec.model.RecommendRequest;
import com.hack.segmentrec.model.RecommendResponse;
import com.hack.segmentrec.service.ArtifactStore;
import com.hack.segmentrec.service.ConversationalRecommendationService;
import com.hack.segmentrec.service.RankingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping("/api")
public class RecommendController {

    private final RankingService rankingService;
    private final ConversationalRecommendationService conversationalRecommendationService;
    private final ArtifactStore artifactStore;

    public RecommendController(
            RankingService rankingService,
            ConversationalRecommendationService conversationalRecommendationService,
            ArtifactStore artifactStore
    ) {
        this.rankingService = rankingService;
        this.conversationalRecommendationService = conversationalRecommendationService;
        this.artifactStore = artifactStore;
    }

    /**
     * Recommend Top-N segments for one client (tenant-isolated).
     * {@code clientName} is required.
     */
    @PostMapping("/recommend/segments")
    public RecommendResponse recommend(@RequestBody RecommendRequest request) {
        if (request.getClientName() == null || request.getClientName().isBlank()) {
            throw new IllegalArgumentException("clientName is required");
        }
        if (request.getTopN() <= 0) {
            request.setTopN(10);
        }
        return rankingService.recommend(request);
    }

    /**
     * Natural-language recommendation flow:
     * query -> parse industry/concept -> retrieve seed segments -> rank final segments.
     */
    @PostMapping("/chat/recommend")
    public ChatRecommendResponse chatRecommend(@RequestBody ChatRecommendRequest request) {
        if (request.getTopN() <= 0) {
            request.setTopN(10);
        }
        return conversationalRecommendationService.recommend(request);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("indexVersion", artifactStore.getIndexVersion());
        body.put("clients", new TreeSet<>(artifactStore.listClientNames()));
        body.put("industries", new TreeSet<>(artifactStore.listIndustries()));
        body.put("globalNameNeighborEntries", artifactStore.nameNeighborEntryCount());
        return body;
    }

    @PostMapping("/admin/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        Map<String, Object> body = new HashMap<>();
        try {
            artifactStore.reload();
            body.put("ok", true);
            body.put("indexVersion", artifactStore.getIndexVersion());
            body.put("clients", new TreeSet<>(artifactStore.listClientNames()));
            return ResponseEntity.ok(body);
        } catch (IOException e) {
            body.put("ok", false);
            body.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("ok", false);
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
