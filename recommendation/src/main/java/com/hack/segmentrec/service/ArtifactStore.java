package com.hack.segmentrec.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hack.segmentrec.config.SegmentRecProperties;
import com.hack.segmentrec.model.ScoredSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three-layer artifacts: global / industries / clients.
 * Client co-occurrence never merges across tenants.
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final SegmentRecProperties properties;
    private final ObjectMapper objectMapper;

    private volatile String indexVersion = "unloaded";
    private volatile GlobalArtifacts global = GlobalArtifacts.empty();
    /** industry name (exact) → popularity list */
    private volatile Map<String, List<ScoredSegment>> industryPopularity = Collections.emptyMap();
    private volatile Map<String, String> industryLookup = Collections.emptyMap();
    private volatile Map<String, ClientArtifacts> byClientName = Collections.emptyMap();
    private volatile Map<String, String> clientLookup = Collections.emptyMap();

    public ArtifactStore(SegmentRecProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (IOException e) {
            log.warn("Artifacts not loaded at startup ({}). Run training first, then POST /admin/reload",
                    e.getMessage());
        }
    }

    public synchronized void reload() throws IOException {
        Path root = Paths.get(properties.getArtifactsPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Artifacts directory not found: " + root);
        }

        String newIndexVersion = "unknown";
        Path indexPath = root.resolve("index.json");
        if (Files.exists(indexPath)) {
            JsonNode index = objectMapper.readTree(indexPath.toFile());
            if (index.has("version")) {
                newIndexVersion = index.get("version").asText();
            }
        }

        GlobalArtifacts g = loadGlobal(root.resolve("global"));
        Map<String, List<ScoredSegment>> industries = new ConcurrentHashMap<>();
        Map<String, String> indLookup = new HashMap<>();
        loadIndustries(root.resolve("industries"), industries, indLookup);

        // Legacy fallback: old global/popularity.json map
        Path legacyPop = root.resolve("global").resolve("popularity.json");
        if (industries.isEmpty() && Files.exists(legacyPop)) {
            Map<String, List<ScoredSegment>> legacy = readMapOfLists(legacyPop);
            for (Map.Entry<String, List<ScoredSegment>> e : legacy.entrySet()) {
                industries.put(e.getKey(), e.getValue());
                indLookup.put(e.getKey().toLowerCase(Locale.ROOT), e.getKey());
            }
            log.info("Loaded legacy global/popularity.json into industry layer ({} industries)", industries.size());
        }

        Map<String, ClientArtifacts> clients = new ConcurrentHashMap<>();
        Map<String, String> cLookup = new HashMap<>();
        Path clientsRoot = root.resolve("clients");
        if (Files.isDirectory(clientsRoot)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(clientsRoot)) {
                for (Path dir : stream) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    ClientArtifacts artifacts = loadClientDir(dir);
                    if (artifacts == null) {
                        continue;
                    }
                    clients.put(artifacts.clientName, artifacts);
                    cLookup.put(artifacts.clientName.toLowerCase(Locale.ROOT), artifacts.clientName);
                    log.info("Client layer loaded '{}' catalog={}", artifacts.clientName, artifacts.segmentCatalog.size());
                }
            }
        }

        this.global = g;
        this.industryPopularity = industries;
        this.industryLookup = indLookup;
        this.byClientName = clients;
        this.clientLookup = cLookup;
        this.indexVersion = newIndexVersion;
        log.info("Reload done version={} global={} industries={} clients={}",
                indexVersion, g.loaded, industries.size(), clients.size());
    }

    private void loadIndustries(
            Path root,
            Map<String, List<ScoredSegment>> out,
            Map<String, String> lookup
    ) throws IOException {
        if (!Files.isDirectory(root)) {
            log.warn("industries/ missing under artifacts");
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Path pop = dir.resolve("popularity.json");
                if (!Files.exists(pop)) {
                    continue;
                }
                String industry = dir.getFileName().toString();
                Path meta = dir.resolve("meta.json");
                if (Files.exists(meta)) {
                    JsonNode node = objectMapper.readTree(meta.toFile());
                    if (node.has("industry")) {
                        industry = node.get("industry").asText();
                    }
                }
                List<ScoredSegment> list = readList(pop);
                out.put(industry, list);
                lookup.put(industry.toLowerCase(Locale.ROOT), industry);
                log.info("Industry layer loaded '{}' segments={}", industry, list.size());
            }
        }
    }

    private GlobalArtifacts loadGlobal(Path dir) throws IOException {
        GlobalArtifacts g = new GlobalArtifacts();
        if (!Files.isDirectory(dir)) {
            log.warn("global/ missing");
            g.loaded = false;
            return g;
        }
        Path prior = dir.resolve("segment_prior.json");
        g.segmentPrior = Files.exists(prior) ? readList(prior) : Collections.emptyList();
        Path namesPath = dir.resolve("segment_names.json");
        g.segmentNames = Files.exists(namesPath) ? readStringMap(namesPath) : Collections.emptyMap();
        Path nameEmb = dir.resolve("segment_name_embeddings.json");
        g.segmentNameEmbeddings = Files.exists(nameEmb) ? readVectorMap(nameEmb) : Collections.emptyMap();
        Path nameNbr = dir.resolve("name_neighbors.json");
        g.nameNeighbors = Files.exists(nameNbr) ? readMapOfLists(nameNbr) : Collections.emptyMap();
        Path metaPath = dir.resolve("meta.json");
        if (Files.exists(metaPath)) {
            JsonNode meta = objectMapper.readTree(metaPath.toFile());
            if (meta.has("version")) {
                g.version = meta.get("version").asText();
            }
        }
        g.loaded = true;
        log.info("Global layer loaded segmentPrior={} nameEmbeddings={} nameNeighbors={}",
                g.segmentPrior.size(), g.segmentNameEmbeddings.size(), g.nameNeighbors.size());
        return g;
    }

    private ClientArtifacts loadClientDir(Path dir) throws IOException {
        Path metaPath = dir.resolve("meta.json");
        if (!Files.exists(metaPath)) {
            log.warn("Skip {}: missing meta.json", dir);
            return null;
        }
        JsonNode meta = objectMapper.readTree(metaPath.toFile());
        String clientName = meta.has("clientName") ? meta.get("clientName").asText() : dir.getFileName().toString();
        String version = meta.has("version") ? meta.get("version").asText() : "unknown";

        ClientArtifacts a = new ClientArtifacts();
        a.clientName = clientName;
        a.version = version;
        a.popularityByIndustry = readMapOfLists(dir.resolve("popularity.json"));
        a.similarity = readMapOfLists(dir.resolve("similarity.json"));
        a.embNeighbors = Files.exists(dir.resolve("emb_neighbors.json"))
                ? readMapOfLists(dir.resolve("emb_neighbors.json"))
                : Collections.emptyMap();
        a.segmentCatalog = readCatalog(dir.resolve("segment_catalog.json"));
        return a;
    }

    private Set<String> readCatalog(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Collections.emptySet();
        }
        JsonNode node = objectMapper.readTree(path.toFile());
        Set<String> ids = new HashSet<>();
        if (node.has("segmentIds") && node.get("segmentIds").isArray()) {
            for (JsonNode id : node.get("segmentIds")) {
                ids.add(id.asText());
            }
        }
        return ids;
    }

    private Map<String, List<ScoredSegment>> readMapOfLists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Collections.emptyMap();
        }
        Map<String, List<ScoredSegment>> raw = objectMapper.readValue(
                path.toFile(),
                new TypeReference<Map<String, List<ScoredSegment>>>() {
                });
        return raw != null ? raw : new HashMap<>();
    }

    private List<ScoredSegment> readList(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }
        List<ScoredSegment> raw = objectMapper.readValue(
                path.toFile(),
                new TypeReference<List<ScoredSegment>>() {
                });
        return raw != null ? raw : Collections.emptyList();
    }

    private Map<String, String> readStringMap(Path path) throws IOException {
        Map<String, String> raw = objectMapper.readValue(
                path.toFile(),
                new TypeReference<Map<String, String>>() {
                });
        return raw != null ? raw : new HashMap<>();
    }

    private Map<String, List<Double>> readVectorMap(Path path) throws IOException {
        Map<String, List<Double>> raw = objectMapper.readValue(
                path.toFile(),
                new TypeReference<Map<String, List<Double>>>() {
                });
        return raw != null ? raw : new HashMap<>();
    }

    public String getIndexVersion() {
        return indexVersion;
    }

    public GlobalArtifacts getGlobal() {
        return global;
    }

    public int nameNeighborEntryCount() {
        return global.nameNeighbors.size();
    }

    public List<ScoredSegment> industryPopularityFor(String industry) {
        if (industry == null || industry.isBlank()) {
            return Collections.emptyList();
        }
        String exact = industryLookup.get(industry.trim().toLowerCase(Locale.ROOT));
        if (exact == null) {
            return Collections.emptyList();
        }
        return industryPopularity.getOrDefault(exact, Collections.emptyList());
    }

    public Set<String> listClientNames() {
        return Collections.unmodifiableSet(byClientName.keySet());
    }

    public Set<String> listIndustries() {
        return Collections.unmodifiableSet(industryPopularity.keySet());
    }

    public Map<String, String> globalSegmentNames() {
        return Collections.unmodifiableMap(global.segmentNames);
    }

    public ClientArtifacts requireClient(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalArgumentException("clientName is required");
        }
        String exact = clientLookup.get(clientName.trim().toLowerCase(Locale.ROOT));
        if (exact == null) {
            throw new IllegalArgumentException("Unknown clientName: " + clientName);
        }
        return byClientName.get(exact);
    }

    public static final class GlobalArtifacts {
        private boolean loaded;
        private String version = "none";
        private List<ScoredSegment> segmentPrior = Collections.emptyList();
        private Map<String, String> segmentNames = Collections.emptyMap();
        private Map<String, List<Double>> segmentNameEmbeddings = Collections.emptyMap();
        private Map<String, List<ScoredSegment>> nameNeighbors = Collections.emptyMap();

        static GlobalArtifacts empty() {
            GlobalArtifacts g = new GlobalArtifacts();
            g.loaded = false;
            return g;
        }

        public boolean isLoaded() {
            return loaded;
        }

        public String getVersion() {
            return version;
        }

        public List<ScoredSegment> getSegmentPrior() {
            return segmentPrior;
        }

        public Map<String, String> getSegmentNames() {
            return segmentNames;
        }

        public Map<String, List<Double>> getSegmentNameEmbeddings() {
            return segmentNameEmbeddings;
        }

        public List<ScoredSegment> nameNeighborsOf(String segmentId) {
            return nameNeighbors.getOrDefault(segmentId, Collections.emptyList());
        }
    }

    public static final class ClientArtifacts {
        private String clientName;
        private String version;
        private Map<String, List<ScoredSegment>> popularityByIndustry = Collections.emptyMap();
        private Map<String, List<ScoredSegment>> similarity = Collections.emptyMap();
        private Map<String, List<ScoredSegment>> embNeighbors = Collections.emptyMap();
        private Set<String> segmentCatalog = Collections.emptySet();

        public String getClientName() {
            return clientName;
        }

        public String getVersion() {
            return version;
        }

        public List<ScoredSegment> popularityFor(String industry) {
            if (industry == null || industry.isBlank()) {
                Map<String, ScoredSegment> best = new HashMap<>();
                for (List<ScoredSegment> list : popularityByIndustry.values()) {
                    for (ScoredSegment s : list) {
                        best.merge(s.getSegmentId(), s, (a, b) -> a.getScore() >= b.getScore() ? a : b);
                    }
                }
                return List.copyOf(best.values());
            }
            List<ScoredSegment> list = popularityByIndustry.get(industry);
            return list != null ? list : Collections.emptyList();
        }

        /** Industries this client has popularity data for; a tenant may span several. */
        public Set<String> getIndustries() {
            return Collections.unmodifiableSet(popularityByIndustry.keySet());
        }

        /**
         * Where this client's activity concentrates, used when the caller does not pass an
         * industry. Compares absolute selection counts rather than popularity scores, because
         * scores are min-max normalized within each industry and so are not comparable across
         * them. Returns null when the client has no popularity data.
         */
        public String primaryIndustry() {
            String best = null;
            long bestCount = -1;
            for (Map.Entry<String, List<ScoredSegment>> entry : popularityByIndustry.entrySet()) {
                long total = 0;
                for (ScoredSegment segment : entry.getValue()) {
                    total += segment.getCount() != null ? segment.getCount() : 1;
                }
                if (total > bestCount || (total == bestCount && entry.getKey().compareTo(best) < 0)) {
                    best = entry.getKey();
                    bestCount = total;
                }
            }
            return best;
        }

        public List<ScoredSegment> similarTo(String segmentId) {
            return similarity.getOrDefault(segmentId, Collections.emptyList());
        }

        public List<ScoredSegment> embeddingNeighbors(String segmentId) {
            return embNeighbors.getOrDefault(segmentId, Collections.emptyList());
        }

        public boolean isInCatalog(String segmentId) {
            return segmentCatalog.contains(segmentId);
        }

        public Set<String> getSegmentCatalog() {
            return Collections.unmodifiableSet(segmentCatalog);
        }
    }
}
