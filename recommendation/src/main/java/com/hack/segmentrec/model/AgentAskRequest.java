package com.hack.segmentrec.model;

import java.util.ArrayList;
import java.util.List;

public class AgentAskRequest {

    /** Required tenant key. Never inferred by the LLM. */
    private String clientName;
    private String query;
    private String industry;
    /**
     * Segments the user already picked in the UI. Supplied by the caller rather than the
     * LLM, which has no way to know segment ids, and used as lookalike seeds.
     */
    private List<String> selectedSegmentIds = new ArrayList<>();
    private int topN = 10;
    private boolean expandBeyondCatalog = false;

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public List<String> getSelectedSegmentIds() {
        return selectedSegmentIds;
    }

    public void setSelectedSegmentIds(List<String> selectedSegmentIds) {
        this.selectedSegmentIds = selectedSegmentIds == null ? new ArrayList<>() : selectedSegmentIds;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public boolean isExpandBeyondCatalog() {
        return expandBeyondCatalog;
    }

    public void setExpandBeyondCatalog(boolean expandBeyondCatalog) {
        this.expandBeyondCatalog = expandBeyondCatalog;
    }
}
