package com.hack.segmentrec.model;

public class ChatRecommendRequest {

    private String clientName;
    private String query;
    private String industry;
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
