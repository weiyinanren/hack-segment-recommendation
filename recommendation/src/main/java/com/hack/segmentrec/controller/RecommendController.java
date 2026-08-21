package com.hack.segmentrec.controller;

import com.hack.segmentrec.model.AgentAskRequest;
import com.hack.segmentrec.model.AgentAskResponse;
import com.hack.segmentrec.model.RecommendRequest;
import com.hack.segmentrec.model.RecommendResponse;
import com.hack.segmentrec.service.ArtifactStore;
import com.hack.segmentrec.service.RankingService;
import com.hack.segmentrec.service.agent.SegmentToolRouter;
import com.hack.segmentrec.service.agent.tools.ServiceHealthTool;
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
    private final SegmentToolRouter segmentToolRouter;
    private final ServiceHealthTool serviceHealthTool;
    private final ArtifactStore artifactStore;

    public RecommendController(
            RankingService rankingService,
            SegmentToolRouter segmentToolRouter,
            ServiceHealthTool serviceHealthTool,
            ArtifactStore artifactStore
    ) {
        this.rankingService = rankingService;
        this.segmentToolRouter = segmentToolRouter;
        this.serviceHealthTool = serviceHealthTool;
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
     * Natural-language entry point: Gemini decides which capability of this service
     * answers the query, invokes it, and summarizes the result. The chat flow it used to
     * sit next to is now reachable only as the {@code chat_recommend} tool behind this
     * router, so callers have one natural-language door instead of two.
     */
    @PostMapping("/audience/intelligence")
    public AgentAskResponse ask(@RequestBody AgentAskRequest request) {
        return segmentToolRouter.ask(request);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = serviceHealthTool.snapshot();
        body.put("agentTools", segmentToolRouter.availableToolNames());
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
