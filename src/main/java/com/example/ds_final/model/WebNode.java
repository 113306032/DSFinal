package com.example.ds_final.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class WebNode {
    public WebNode parent;
    public ArrayList<WebNode> children;
    public String url;
    public String title;
    public double nodeScore;
    public Map<String, Integer> keywordCounts;

    public WebNode(String url, String title) {
        this.url = url;
        this.title = title;
        this.children = new ArrayList<>();
        this.keywordCounts = new HashMap<>();
        this.nodeScore = 0;
    }

    // Stage 1 & Strategy: 計算分數 + 權威加權
    public void setNodeScore(ArrayList<String> keywords) {
        double score = 0;
        
        // 1. 基礎關鍵字計分
        for (String k : keywords) {
            // 轉小寫比對
            int count = keywordCounts.getOrDefault(k, 0); 
            score += count * 2; // 每個關鍵字給 2 分
        }

        // 2. 權威網站加權 (Authority Weighting) - 這是過濾雜訊的關鍵
        String lowerUrl = this.url.toLowerCase();
        boolean isAuthority = false;
        
        if (lowerUrl.contains("imdb.com") || 
            lowerUrl.contains("rottentomatoes.com") || 
            lowerUrl.contains("metacritic.com") ||
            lowerUrl.contains("douban.com") ||
            lowerUrl.contains("wikipedia.org") ||
            lowerUrl.contains("rogerebert.com") ||
            lowerUrl.contains("commonsensemedia.org")) {
            
            score += 50; // 基礎分直接加 50
            score *= 5;  // 總分再翻 5 倍
            isAuthority = true;
        }

        // 3. 標題相關性加權
        String lowerTitle = this.title.toLowerCase();
        if (lowerTitle.contains("review") || lowerTitle.contains("rating") || lowerTitle.contains("cast")) {
            score += 20;
        }

        // 如果既不是權威網站，分數又很低，可能是雜訊，給予懲罰
        if (!isAuthority && score < 10) {
            score = 0; 
        }

        this.nodeScore = score;
    }
}