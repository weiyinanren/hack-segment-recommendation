package com.hack.segmentrec.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ScoredSegment {

    private String segmentId;
    private double score;
    private Integer count;
    private Integer coOccurrence;

    public ScoredSegment() {
    }

    public ScoredSegment(String segmentId, double score) {
        this.segmentId = segmentId;
        this.score = score;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(String segmentId) {
        this.segmentId = segmentId;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public Integer getCoOccurrence() {
        return coOccurrence;
    }

    public void setCoOccurrence(Integer coOccurrence) {
        this.coOccurrence = coOccurrence;
    }
}
