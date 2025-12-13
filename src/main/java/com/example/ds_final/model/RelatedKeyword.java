package com.example.ds_final.model;

public class RelatedKeyword {
    public String term;
    public double score;
    public int docCount;

    public RelatedKeyword(String term, double score, int docCount) {
        this.term = term;
        this.score = score;
        this.docCount = docCount;
    }
}
