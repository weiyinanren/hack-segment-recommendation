package com.hack.segmentrec.service;

import com.hack.segmentrec.model.ChatRecommendRequest;
import com.hack.segmentrec.model.ChatRecommendResponse;
import com.hack.segmentrec.model.ChatRecommendResponse.ParsedQuery;
import com.hack.segmentrec.model.ChatRecommendResponse.SeedSegment;
import com.hack.segmentrec.model.RecommendRequest;
import com.hack.segmentrec.model.RecommendResponse;
import com.hack.segmentrec.service.query.QueryParseResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationalRecommendationService {

    private final ArtifactStore artifactStore;
    private final QueryUnderstandingService queryUnderstandingService;
    private final ConceptRetrievalService conceptRetrievalService;
    private final RankingService rankingService;

    public ConversationalRecommendationService(
            ArtifactStore artifactStore,
            QueryUnderstandingService queryUnderstandingService,
            ConceptRetrievalService conceptRetrievalService,
            RankingService rankingService
    ) {
        this.artifactStore = artifactStore;
        this.queryUnderstandingService = queryUnderstandingService;
        this.conceptRetrievalService = conceptRetrievalService;
        this.rankingService = rankingService;
    }

    public ChatRecommendResponse recommend(ChatRecommendRequest request) {
        if (request.getClientName() == null || request.getClientName().isBlank()) {
            throw new IllegalArgumentException("clientName is required");
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }

        ArtifactStore.ClientArtifacts client = artifactStore.requireClient(request.getClientName());
        QueryParseResult parsed = queryUnderstandingService.parse(
                request.getQuery(),
                request.getIndustry(),
                artifactStore.listIndustries()
        );

        List<SeedSegment> seeds = conceptRetrievalService.retrieve(
                parsed.getConcept(),
                client,
                request.isExpandBeyondCatalog(),
                Math.max(3, Math.min(request.getTopN(), 8)),
                parsed.getExcludeConcepts()
        );

        RecommendRequest rankingRequest = new RecommendRequest();
        rankingRequest.setClientName(request.getClientName());
        rankingRequest.setIndustry(parsed.getIndustry());
        rankingRequest.setExpandBeyondCatalog(request.isExpandBeyondCatalog());
        rankingRequest.setTopN(request.getTopN() <= 0 ? 10 : request.getTopN());
        rankingRequest.setExcludeConcepts(parsed.getExcludeConcepts());
        rankingRequest.setSelectedSegmentIds(
                seeds.stream().map(SeedSegment::getSegmentId).collect(Collectors.toList())
        );

        RecommendResponse recommendations = rankingService.recommend(rankingRequest);

        ChatRecommendResponse response = new ChatRecommendResponse();
        ParsedQuery parsedPayload = new ParsedQuery();
        parsedPayload.setOriginalQuery(parsed.getOriginalQuery());
        parsedPayload.setIndustry(parsed.getIndustry());
        parsedPayload.setConcept(parsed.getConcept());
        parsedPayload.setExcludeConcepts(parsed.getExcludeConcepts());
        parsedPayload.setUnderstandingStrategy(parsed.getStrategy());
        response.setParsedQuery(parsedPayload);
        response.setSeedSegments(seeds);
        response.setRecommendations(recommendations);
        return response;
    }
}
