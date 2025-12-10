package com.example.ds_final.service;

import com.example.ds_final.model.WebNode;
import com.example.ds_final.model.WebTree;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    // TODO: 請在此填入你的 Google API 資訊
    private final String GOOGLE_API_KEY = "AIzaSyA8FVvvz-QF_akeNpO8offtBpJq-_D13xY";
    private final String SEARCH_ENGINE_ID = "952752be890eb41ec";

    // Stage 3: Google Search (JSON 解析修復版)
    public List<String> googleSearch(String query) {
        List<String> urls = new ArrayList<>();
        try {
            // 策略：強制加上 review 並排除論壇字眼，讓結果更純淨
            String searchStrategy = query + " movie review -forum -thread -discussion -login";
            System.out.println("Google Searching for: " + searchStrategy);
            
            String encodedQuery = URLEncoder.encode(searchStrategy, StandardCharsets.UTF_8);
            String urlStr = "https://www.googleapis.com/customsearch/v1?key=" + GOOGLE_API_KEY + "&cx=" + SEARCH_ENGINE_ID + "&q=" + encodedQuery;

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);

            if (conn.getResponseCode() != 200) {
                System.err.println("Google API Error: " + conn.getResponseCode());
                return urls;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder content = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();

            // 使用 Jackson 解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(content.toString());
            JsonNode items = rootNode.path("items");

            if (items.isArray()) {
                for (JsonNode item : items) {
                    String link = item.path("link").asText();
                    urls.add(link);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return urls;
    }

    // Stage 2: 建立樹狀結構與爬蟲 (User-Agent 修復版)
    public WebTree processSite(String rootUrl, ArrayList<String> keywords) {
        try {
            // 設定 User-Agent 偽裝成瀏覽器
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

            Document doc = Jsoup.connect(rootUrl)
                    .userAgent(userAgent)
                    .timeout(5000)
                    .ignoreHttpErrors(true)
                    .get();

            String title = doc.title();
            if (title == null || title.isEmpty()) title = rootUrl;

            WebNode rootNode = new WebNode(rootUrl, title);
            countKeywords(rootNode, doc.text(), keywords);
            rootNode.setNodeScore(keywords);

            // 尋找子連結 (限制 2 個以加速)
            Elements links = doc.select("a[href^=http]");
            int count = 0;
            for (Element link : links) {
                if (count >= 2) break;
                String subUrl = link.attr("href");
                
                // 簡單過濾：不要爬回首頁，不要爬登入頁
                if (subUrl.equals(rootUrl) || subUrl.contains("login") || subUrl.contains("signup")) continue;

                try {
                    Document subDoc = Jsoup.connect(subUrl)
                            .userAgent(userAgent)
                            .timeout(3000)
                            .ignoreHttpErrors(true)
                            .get();

                    WebNode childNode = new WebNode(subUrl, subDoc.title());
                    countKeywords(childNode, subDoc.text(), keywords);
                    childNode.setNodeScore(keywords);
                    rootNode.children.add(childNode);
                    count++;
                } catch (Exception ignored) {}
            }

            WebTree tree = new WebTree(rootNode);
            tree.calculateSiteScore();
            return tree;

        } catch (Exception e) {
            // 許多網站無法爬取是正常的，回傳 null 讓 Controller 忽略它
            return null;
        }
    }

    // Stage 1 Helper: 計算關鍵字次數
    private void countKeywords(WebNode node, String text, ArrayList<String> keywords) {
        String lowerText = text.toLowerCase();
        for (String k : keywords) {
            // 這裡傳進來的 keywords 已經是小寫了嗎？在 Controller 處理比較好，或是這裡轉
            // 簡單計算
            int count = 0;
            int idx = 0;
            while ((idx = lowerText.indexOf(k, idx)) != -1) {
                count++;
                idx += k.length();
            }
            node.keywordCounts.put(k, count);
        }
    }

    // Stage 4: 語意分析 (從結果中找高頻字)
    public List<String> deriveRelatedKeywords(List<WebTree> rankedSites) {
        Map<String, Integer> wordFreq = new HashMap<>();
        
        // 排除常見無意義字
        Set<String> stopWords = Set.of("movie", "review", "film", "imdb", "login", "search", "menu", "watch", "trailer", "the", "and", "of", "rating");

        for (WebTree tree : rankedSites) {
            // 只分析前 5 名的標題
            if (rankedSites.indexOf(tree) > 4) break;
            
            String[] words = tree.root.title.toLowerCase().split("[^a-zA-Z]+"); // 只留英文字母
            for (String w : words) {
                if (w.length() > 3 && !stopWords.contains(w)) {
                    wordFreq.put(w, wordFreq.getOrDefault(w, 0) + 1);
                }
            }
        }

        return wordFreq.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}