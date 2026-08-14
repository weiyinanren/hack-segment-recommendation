package com.hack.segmentrec.service;

import com.hack.segmentrec.config.SegmentRecProperties;
import com.hack.segmentrec.model.RecommendRequest;
import com.hack.segmentrec.model.RecommendResponse;
import com.hack.segmentrec.model.RecommendResponse.RecommendedItem;
import com.hack.segmentrec.model.ScoredSegment;
import com.hack.segmentrec.service.ArtifactStore.ClientArtifacts;
import com.hack.segmentrec.service.ArtifactStore.GlobalArtifacts;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Three-layer mix + name similarity:
 *   score = wG*global + wI*industry + wC*clientPop + wSim*sim + wEmb*emb + wName*name
 *
 * Name channel links synonyms like miss / Ms. / female (global name_neighbors).
 * Default: only client catalog; expandBeyondCatalog may surface outside-catalog names.
 */
@Service
public class RankingService {

    private final ArtifactStore artifactStore;
    private final SegmentRecProperties properties;

    public RankingService(ArtifactStore artifactStore, SegmentRecProperties properties) {
        this.artifactStore = artifactStore;
        this.properties = properties;
    }

    public RecommendResponse recommend(RecommendRequest request) {
        ClientArtifacts client = artifactStore.requireClient(request.getClientName());
        GlobalArtifacts global = artifactStore.getGlobal();
        boolean expand = request.isExpandBeyondCatalog();

        int topN = Math.max(1, Math.min(request.getTopN(), 100));
        List<String> selected = request.getSelectedSegmentIds();
        Set<String> exclude = new HashSet<>();
        if (request.isExcludeSelected()) {
            exclude.addAll(selected);
        }

        // [global, industry, clientPop, sim, emb, name]
        Map<String, double[]> scores = new HashMap<>();

        for (ScoredSegment s : global.getSegmentPrior()) {
            if (!eligible(client, s.getSegmentId(), expand)) {
                continue;
            }
            bump(scores, s.getSegmentId(), 0, s.getScore());
        }

        for (ScoredSegment s : artifactStore.industryPopularityFor(request.getIndustry())) {
            if (!eligible(client, s.getSegmentId(), expand)) {
                continue;
            }
            bump(scores, s.getSegmentId(), 1, s.getScore());
        }

        for (ScoredSegment s : client.popularityFor(request.getIndustry())) {
            if (!eligible(client, s.getSegmentId(), expand)) {
                continue;
            }
            bump(scores, s.getSegmentId(), 2, s.getScore());
        }

        boolean hasSelection = selected != null && !selected.isEmpty();
        if (hasSelection) {
            for (String selectedId : selected) {
                if (client.isInCatalog(selectedId)) {
                    for (ScoredSegment s : client.similarTo(selectedId)) {
                        if (!eligible(client, s.getSegmentId(), expand)) {
                            continue;
                        }
                        bump(scores, s.getSegmentId(), 3, s.getScore());
                    }
                    for (ScoredSegment s : client.embeddingNeighbors(selectedId)) {
                        if (!eligible(client, s.getSegmentId(), expand)) {
                            continue;
                        }
                        bump(scores, s.getSegmentId(), 4, s.getScore());
                    }
                }
                // Name similarity is global (synonyms); seed can be any known segment id
                for (ScoredSegment s : global.nameNeighborsOf(selectedId)) {
                    if (!eligible(client, s.getSegmentId(), expand)) {
                        continue;
                    }
                    bump(scores, s.getSegmentId(), 5, s.getScore());
                }
            }
        }

        SegmentRecProperties.Weights w = properties.getWeights();
        double wG = w.getGlobalPopularity();
        double wI = w.getIndustryPopularity();
        double wC = w.getClientPopularity();
        double wSim = hasSelection ? w.getSimilarity() : 0.0;
        double wEmb = hasSelection ? w.getEmbedding() : 0.0;
        double wName = hasSelection ? w.getNameSimilarity() : 0.0;

        List<RecommendedItem> items = scores.entrySet().stream()
                .filter(e -> !exclude.contains(e.getKey()))
                .filter(e -> eligible(client, e.getKey(), expand))
                .map(e -> {
                    double[] c = e.getValue();
                    RecommendedItem item = new RecommendedItem();
                    item.setSegmentId(e.getKey());
                    item.setInCatalog(client.isInCatalog(e.getKey()));
                    item.setGlobalPopularityScore(round(c[0]));
                    item.setIndustryPopularityScore(round(c[1]));
                    item.setClientPopularityScore(round(c[2]));
                    item.setSimilarityScore(round(c[3]));
                    item.setEmbeddingScore(round(c[4]));
                    item.setNameSimilarityScore(round(c[5]));
                    item.setScore(round(
                            wG * c[0] + wI * c[1] + wC * c[2] + wSim * c[3] + wEmb * c[4] + wName * c[5]));
                    return item;
                })
                .sorted(Comparator.comparingDouble(RecommendedItem::getScore).reversed()
                        .thenComparing(RecommendedItem::getSegmentId))
                .limit(topN)
                .collect(Collectors.toList());

        RecommendResponse response = new RecommendResponse();
        response.setClientName(client.getClientName());
        response.setVersion(client.getVersion());
        response.setExpandBeyondCatalog(expand);
        response.setStrategy(strategyLabel(hasSelection, expand));
        response.setItems(items);
        return response;
    }

    private static String strategyLabel(boolean hasSelection, boolean expand) {
        if (expand) {
            return hasSelection ? "three_layer_name_expand_fusion" : "three_layer_expand_cold_start";
        }
        return hasSelection ? "three_layer_name_catalog_fusion" : "three_layer_catalog_cold_start";
    }

    private static boolean eligible(ClientArtifacts client, String segmentId, boolean expand) {
        return expand || client.isInCatalog(segmentId);
    }

    private static void bump(Map<String, double[]> scores, String segmentId, int channel, double value) {
        double[] b = scores.computeIfAbsent(segmentId, k -> new double[6]);
        b[channel] = Math.max(b[channel], value);
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
