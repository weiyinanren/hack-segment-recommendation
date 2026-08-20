package com.hack.segmentrec.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.service.ArtifactStore;
import com.hack.segmentrec.service.agent.SegmentTool;
import com.hack.segmentrec.service.agent.ToolContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

@Component
public class ReloadArtifactsTool implements SegmentTool {

    private final ArtifactStore artifactStore;

    public ReloadArtifactsTool(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @Override
    public String name() {
        return "reload_artifacts";
    }

    @Override
    public String description() {
        return "Hot-reload the model artifacts from disk. Pick this only when the user explicitly asks to reload, "
                + "refresh or re-import the model data.";
    }

    @Override
    public ObjectNode parameterSchema(ObjectMapper mapper) {
        return null;
    }

    @Override
    public boolean isAdmin() {
        return true;
    }

    @Override
    public Object invoke(JsonNode arguments, ToolContext context) {
        Map<String, Object> body = new HashMap<>();
        try {
            artifactStore.reload();
            body.put("ok", true);
            body.put("indexVersion", artifactStore.getIndexVersion());
            body.put("clients", new TreeSet<>(artifactStore.listClientNames()));
        } catch (IOException e) {
            body.put("ok", false);
            body.put("error", e.getMessage());
        }
        return body;
    }
}
