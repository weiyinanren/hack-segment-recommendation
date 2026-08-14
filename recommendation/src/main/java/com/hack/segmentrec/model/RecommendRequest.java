package com.hack.segmentrec.model;

import java.util.ArrayList;
import java.util.List;

public class RecommendRequest {

    /** Required tenant key. */
    private String clientName;
    private String industry;
    private List<String> selectedSegmentIds = new ArrayList<>();
    private int topN = 10;
    private boolean excludeSelected = true;

    /**
     * false (default): only recommend segments in this client's catalog.
     * true: also allow industry/global prior candidates outside the catalog
     *       (marked {@code inCatalog=false} for UI / entitlement flows).
     */
    private boolean expandBeyondCatalog = false;

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
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
        this.selectedSegmentIds = selectedSegmentIds != null ? selectedSegmentIds : new ArrayList<>();
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public boolean isExcludeSelected() {
        return excludeSelected;
    }

    public void setExcludeSelected(boolean excludeSelected) {
        this.excludeSelected = excludeSelected;
    }

    public boolean isExpandBeyondCatalog() {
        return expandBeyondCatalog;
    }

    public void setExpandBeyondCatalog(boolean expandBeyondCatalog) {
        this.expandBeyondCatalog = expandBeyondCatalog;
    }
}
