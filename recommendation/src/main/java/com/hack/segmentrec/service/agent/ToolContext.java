package com.hack.segmentrec.service.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Caller-owned values for a tool invocation. The tenant in particular is taken
 * from the request and never from the model, so a crafted query cannot cross tenants.
 */
public class ToolContext {

    private final String clientName;
    private final String originalQuery;
    private final String industry;
    private final List<String> selectedSegmentIds;
    private final int topN;
    private final boolean expandBeyondCatalog;

    public ToolContext(
            String clientName,
            String originalQuery,
            String industry,
            List<String> selectedSegmentIds,
            int topN,
            boolean expandBeyondCatalog
    ) {
        this.clientName = clientName;
        this.originalQuery = originalQuery;
        this.industry = industry;
        this.selectedSegmentIds = selectedSegmentIds == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(selectedSegmentIds));
        this.topN = topN;
        this.expandBeyondCatalog = expandBeyondCatalog;
    }

    public String getClientName() {
        return clientName;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getIndustry() {
        return industry;
    }

    public List<String> getSelectedSegmentIds() {
        return selectedSegmentIds;
    }

    public int getTopN() {
        return topN;
    }

    public boolean isExpandBeyondCatalog() {
        return expandBeyondCatalog;
    }
}
