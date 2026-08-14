package com.hack.segmentrec.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "segment-rec")
public class SegmentRecProperties {

    private String artifactsPath = "../artifacts";
    private Weights weights = new Weights();

    public String getArtifactsPath() {
        return artifactsPath;
    }

    public void setArtifactsPath(String artifactsPath) {
        this.artifactsPath = artifactsPath;
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }

    /**
     * score = wGlobal * global + wIndustry * industry + wClientPop * clientPop
     *       + wSim * sim + wEmb * emb
     */
    public static class Weights {
        private double globalPopularity = 0.15;
        private double industryPopularity = 0.25;
        private double clientPopularity = 0.15;
        private double similarity = 0.30;
        private double embedding = 0.12;
        private double nameSimilarity = 0.18;

        public double getGlobalPopularity() {
            return globalPopularity;
        }

        public void setGlobalPopularity(double globalPopularity) {
            this.globalPopularity = globalPopularity;
        }

        public double getIndustryPopularity() {
            return industryPopularity;
        }

        public void setIndustryPopularity(double industryPopularity) {
            this.industryPopularity = industryPopularity;
        }

        public double getClientPopularity() {
            return clientPopularity;
        }

        public void setClientPopularity(double clientPopularity) {
            this.clientPopularity = clientPopularity;
        }

        public double getSimilarity() {
            return similarity;
        }

        public void setSimilarity(double similarity) {
            this.similarity = similarity;
        }

        public double getEmbedding() {
            return embedding;
        }

        public void setEmbedding(double embedding) {
            this.embedding = embedding;
        }

        public double getNameSimilarity() {
            return nameSimilarity;
        }

        public void setNameSimilarity(double nameSimilarity) {
            this.nameSimilarity = nameSimilarity;
        }
    }
}
