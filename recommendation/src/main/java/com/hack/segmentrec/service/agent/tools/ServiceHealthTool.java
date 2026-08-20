package com.hack.segmentrec.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.service.ArtifactStore;
import com.hack.segmentrec.service.agent.SegmentTool;
import com.hack.segmentrec.service.agent.ToolContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

@Component
public class ServiceHealthTool implements SegmentTool {

    private final ArtifactStore artifactStore;

    public ServiceHealthTool(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @Override
    public String name() {
        return "service_health";
    }

    @Override
    public String description() {
        return "Report service status, the loaded artifact index version, and which client names and industries "
                + "are available. Pick this for meta questions such as \"有哪些行业可以选\", \"支持哪些客户\" or "
                + "\"服务正常吗\", which ask about the service itself rather than for segments.";
    }

    @Override
    public ObjectNode parameterSchema(ObjectMapper mapper) {
        return null;
    }

    @Override
    public Object invoke(JsonNode arguments, ToolContext context) {
        return snapshot();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("indexVersion", artifactStore.getIndexVersion());
        body.put("clients", new TreeSet<>(artifactStore.listClientNames()));
        body.put("industries", new TreeSet<>(artifactStore.listIndustries()));
        body.put("globalNameNeighborEntries", artifactStore.nameNeighborEntryCount());
        return body;
    }
}
