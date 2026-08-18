package com.hack.segmentrec.service.query;

import java.util.ArrayList;
import java.util.List;

public class QueryParseResult {

    private String originalQuery;
    private String industry;
    private String concept;
    private List<String> excludeConcepts = new ArrayList<>();
    private String strategy;

    public String getOriginalQuery() {
        return originalQuery;
    }

    public void setOriginalQuery(String originalQuery) {
        this.originalQuery = originalQuery;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getConcept() {
        return concept;
    }

    public void setConcept(String concept) {
        this.concept = concept;
    }

    public List<String> getExcludeConcepts() {
        return excludeConcepts;
    }

    public void setExcludeConcepts(List<String> excludeConcepts) {
        this.excludeConcepts = excludeConcepts != null ? excludeConcepts : new ArrayList<>();
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }
}
