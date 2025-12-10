package com.example.ds_final.model;

import java.util.ArrayList;

public class WebTree {
    public WebNode root;
    public double siteScore;

    public WebTree(WebNode root) {
        this.root = root;
    }

    // Stage 2: 計算整棵樹的分數
    public void calculateSiteScore() {
        this.siteScore = calculateScoreRecursive(root);
    }

    private double calculateScoreRecursive(WebNode node) {
        double score = node.nodeScore;
        
        for (WebNode child : node.children) {
            // 子節點分數衰減係數 0.4
            score += calculateScoreRecursive(child) * 0.4;
        }
        return score;
    }
}