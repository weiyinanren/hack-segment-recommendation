package com.hack.segmentrec.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hack.segmentrec.config.SegmentRecProperties;
import com.hack.segmentrec.model.ChatRecommendResponse.SeedSegment;
import com.hack.segmentrec.service.query.SegmentExclusionFilter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ConceptRetrievalService {

    private final ArtifactStore artifactStore;
    private final SegmentRecProperties properties;
    private final ObjectMapper objectMapper;
    private final SegmentExclusionFilter exclusionFilter;

    public ConceptRetrievalService(
            ArtifactStore artifactStore,
            SegmentRecProperties properties,
            ObjectMapper objectMapper,
            SegmentExclusionFilter exclusionFilter
    ) {
        this.artifactStore = artifactStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.exclusionFilter = exclusionFilter;
    }

    public List<SeedSegment> retrieve(
            String concept,
            ArtifactStore.ClientArtifacts client,
            boolean expandBeyondCatalog,
            int topK
    ) {
        return retrieve(concept, client, expandBeyondCatalog, topK, List.of());
    }

    public List<SeedSegment> retrieve(
            String concept,
            ArtifactStore.ClientArtifacts client,
            boolean expandBeyondCatalog,
            int topK,
            List<String> excludeConcepts
    ) {
        Map<String, String> names = artifactStore.globalSegmentNames();
        if (names.isEmpty()) {
            return List.of();
        }

        String normalizedConcept = normalize(concept);
        Map<String, List<Double>> nameEmbeddings = artifactStore.getGlobal().getSegmentNameEmbeddings();
        List<SeedSegment> embeddingSeeds = embeddingRetrieve(
                concept,
                names,
                nameEmbeddings,
                client,
                expandBeyondCatalog,
                topK,
                excludeConcepts
        );
        if (!embeddingSeeds.isEmpty()) {
            return embeddingSeeds;
        }
        return lexicalRetrieve(normalizedConcept, names, client, expandBeyondCatalog, topK, excludeConcepts);
    }

    private List<SeedSegment> lexicalRetrieve(
            String normalizedConcept,
            Map<String, String> names,
            ArtifactStore.ClientArtifacts client,
            boolean expandBeyondCatalog,
            int topK,
            List<String> excludeConcepts
    ) {
        List<SeedSegment> seeds = new ArrayList<>();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            String segmentId = entry.getKey();
            String segmentName = entry.getValue();
            boolean inCatalog = client.isInCatalog(segmentId);
            if (!expandBeyondCatalog && !inCatalog) {
                continue;
            }
            if (exclusionFilter.isExcluded(segmentId, segmentName, excludeConcepts)) {
                continue;
            }
            double score = conceptSimilarity(normalizedConcept, normalize(segmentName));
            if (score <= 0.0) {
                continue;
            }
            SeedSegment seed = new SeedSegment();
            seed.setSegmentId(segmentId);
            seed.setSegmentName(segmentName);
            seed.setInCatalog(inCatalog);
            seed.setScore(round(score));
            seed.setRetrievalReason("concept_name_match");
            seeds.add(seed);
        }

        seeds.sort(Comparator.comparingDouble(SeedSegment::getScore).reversed()
                .thenComparing(SeedSegment::getSegmentId));
        if (seeds.size() > topK) {
            return new ArrayList<>(seeds.subList(0, topK));
        }
        return seeds;
    }

    private List<SeedSegment> embeddingRetrieve(
            String concept,
            Map<String, String> names,
            Map<String, List<Double>> nameEmbeddings,
            ArtifactStore.ClientArtifacts client,
            boolean expandBeyondCatalog,
            int topK,
            List<String> excludeConcepts
    ) {
        if (concept == null || concept.isBlank() || nameEmbeddings.isEmpty()) {
            return List.of();
        }
        double[] query = embedConcept(concept);
        if (query.length == 0) {
            return List.of();
        }

        int limit = Math.max(1, Math.min(topK, properties.getQueryEmbedding().getTopK()));
        List<SeedSegment> seeds = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : nameEmbeddings.entrySet()) {
            String segmentId = entry.getKey();
            boolean inCatalog = client.isInCatalog(segmentId);
            if (!expandBeyondCatalog && !inCatalog) {
                continue;
            }
            String segmentName = names.getOrDefault(segmentId, segmentId);
            if (exclusionFilter.isExcluded(segmentId, segmentName, excludeConcepts)) {
                continue;
            }
            double score = cosine(query, entry.getValue());
            if (score < properties.getQueryEmbedding().getMinScore()) {
                continue;
            }
            SeedSegment seed = new SeedSegment();
            seed.setSegmentId(segmentId);
            seed.setSegmentName(names.getOrDefault(segmentId, segmentId));
            seed.setInCatalog(inCatalog);
            seed.setScore(round(score));
            seed.setRetrievalReason("concept_embedding_match");
            seeds.add(seed);
        }
        seeds.sort(Comparator.comparingDouble(SeedSegment::getScore).reversed()
                .thenComparing(SeedSegment::getSegmentId));
        return seeds.size() > limit ? new ArrayList<>(seeds.subList(0, limit)) : seeds;
    }

    private double[] embedConcept(String concept) {
        SegmentRecProperties.QueryEmbedding cfg = properties.getQueryEmbedding();
        ProcessBuilder pb = new ProcessBuilder(
                cfg.getPythonPath(),
                cfg.getScriptPath()
        );
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            try (OutputStream os = process.getOutputStream()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("texts", List.of(concept));
                payload.put("model", cfg.getModel());
                objectMapper.writeValue(os, payload);
            }
            byte[] output;
            try (InputStream is = process.getInputStream()) {
                output = is.readAllBytes();
            }
            int exit = process.waitFor();
            if (exit != 0) {
                return new double[0];
            }
            JsonNode root = objectMapper.readTree(new String(output, StandardCharsets.UTF_8));
            JsonNode rows = root.path("embeddings");
            if (!rows.isArray() || rows.isEmpty() || !rows.get(0).isArray()) {
                return new double[0];
            }
            JsonNode row = rows.get(0);
            double[] vector = new double[row.size()];
            for (int i = 0; i < row.size(); i++) {
                vector[i] = row.get(i).asDouble();
            }
            return vector;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new double[0];
        } catch (IOException e) {
            return new double[0];
        }
    }

    private static double cosine(double[] query, List<Double> target) {
        if (query.length == 0 || target == null || target.isEmpty()) {
            return 0.0;
        }
        int len = Math.min(query.length, target.size());
        double dot = 0.0;
        double qn = 0.0;
        double tn = 0.0;
        for (int i = 0; i < len; i++) {
            double q = query[i];
            double t = target.get(i);
            dot += q * t;
            qn += q * q;
            tn += t * t;
        }
        if (qn == 0.0 || tn == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(qn) * Math.sqrt(tn));
    }

    private static double conceptSimilarity(String concept, String target) {
        if (concept.isEmpty() || target.isEmpty()) {
            return 0.0;
        }
        if (target.contains(concept) || concept.contains(target)) {
            return 0.95;
        }

        Set<String> conceptTokens = Set.of(concept.split(" "));
        Set<String> targetTokens = Set.of(target.split(" "));
        long overlap = conceptTokens.stream().filter(targetTokens::contains).count();
        double tokenScore = overlap == 0 ? 0.0 : overlap / (double) Math.max(conceptTokens.size(), targetTokens.size());

        // Light business-concept boosts so "高价值" can hit premium/affluent style names.
        double businessBoost = 0.0;
        String c = concept.toLowerCase(Locale.ROOT);
        String t = target.toLowerCase(Locale.ROOT);
        if (c.contains("高价值") || c.contains("high value") || c.contains("premium")) {
            if (containsAny(t, "premium", "luxury", "affluent", "vip", "loyal", "executive")) {
                businessBoost = 0.85;
            }
        }
        if (c.contains("女性") || c.contains("female") || c.contains("woman") || c.contains("women")) {
            if (containsAny(t, "female", "miss", "ms", "madam", "women", "lady")) {
                businessBoost = Math.max(businessBoost, 0.9);
            }
        }

        double lexical = jaccardCharacterTrigrams(concept, target);
        return Math.max(businessBoost, Math.max(tokenScore, lexical));
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static double jaccardCharacterTrigrams(String a, String b) {
        Set<String> ga = grams(a);
        Set<String> gb = grams(b);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0.0;
        }
        long inter = ga.stream().filter(gb::contains).count();
        long union = ga.size() + gb.size() - inter;
        return union == 0 ? 0.0 : inter / (double) union;
    }

    private static Set<String> grams(String text) {
        String s = " " + text + " ";
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i < Math.max(1, s.length() - 2); i++) {
            int end = Math.min(i + 3, s.length());
            out.add(s.substring(i, end));
        }
        return out;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fff]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
