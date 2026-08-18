package com.hack.segmentrec.service.query;

import com.hack.segmentrec.service.ArtifactStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Filters segments that match negative intent from chat queries
 * (e.g. "不想要女性" → exclude female / women / miss-style names).
 */
@Component
public class SegmentExclusionFilter {

    private static final Map<String, List<String>> EXCLUSION_ALIASES = Map.of(
            "female", List.of("female", "women", "woman", "miss", "ms", "mrs", "madam", "lady", "女性", "女人", "女士"),
            "male", List.of("male", "men", "man", "mr", "男性", "男人"),
            "child", List.of("child", "children", "kids", "kid", "baby", "儿童", "孩子"),
            "elder", List.of("elder", "senior", "elderly", "老年", "老人")
    );

    private final ArtifactStore artifactStore;

    public SegmentExclusionFilter(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    public boolean isExcluded(String segmentId, String segmentName, List<String> excludeConcepts) {
        if (excludeConcepts == null || excludeConcepts.isEmpty()) {
            return false;
        }
        String name = segmentName != null ? segmentName : segmentId;
        String normalizedName = normalize(name);
        String normalizedId = normalize(segmentId);

        Set<String> needles = expandExcludeTerms(excludeConcepts);
        for (String needle : needles) {
            if (needle.isEmpty()) {
                continue;
            }
            if (lexicalMatch(normalizedName, needle) || lexicalMatch(normalizedId, needle)) {
                return true;
            }
        }
        return false;
    }

    public List<String> filterSegmentIds(List<String> segmentIds, List<String> excludeConcepts) {
        if (excludeConcepts == null || excludeConcepts.isEmpty()) {
            return segmentIds;
        }
        Map<String, String> names = artifactStore.globalSegmentNames();
        List<String> kept = new ArrayList<>();
        for (String id : segmentIds) {
            if (!isExcluded(id, names.getOrDefault(id, id), excludeConcepts)) {
                kept.add(id);
            }
        }
        return kept;
    }

    static Set<String> expandExcludeTerms(List<String> excludeConcepts) {
        Set<String> out = new HashSet<>();
        for (String raw : excludeConcepts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalized = normalize(raw);
            out.add(normalized);
            for (Map.Entry<String, List<String>> entry : EXCLUSION_ALIASES.entrySet()) {
                String key = entry.getKey();
                boolean related = normalized.contains(key)
                        || entry.getValue().stream().anyMatch(alias -> normalized.contains(normalize(alias)));
                if (related) {
                    out.add(normalize(key));
                    for (String alias : entry.getValue()) {
                        out.add(normalize(alias));
                    }
                }
            }
        }
        return out;
    }

    static boolean lexicalMatch(String target, String needle) {
        if (target.isEmpty() || needle.isEmpty()) {
            return false;
        }
        if (target.equals(needle)) {
            return true;
        }
        Set<String> targetTokens = Set.of(target.split(" "));
        if (targetTokens.contains(needle)) {
            return true;
        }
        boolean cjk = needle.chars().anyMatch(ch -> ch >= 0x4E00 && ch <= 0x9FFF);
        return (cjk || needle.length() >= 4) && target.contains(needle);
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fff]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
