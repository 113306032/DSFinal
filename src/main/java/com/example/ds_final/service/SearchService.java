package com.example.ds_final.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.ds_final.model.RelatedKeyword;
import com.example.ds_final.model.WebNode;
import com.example.ds_final.model.WebTree;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class SearchService {

    // TODO: 請在此填入你的 Google API 資訊
    @Value("${google.api.key}")
    private String GOOGLE_API_KEY;

    @Value("${google.api.cx}")
    private String SEARCH_ENGINE_ID;

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
            // 立即過濾已知不需要的來源（例如 Wikimedia 捐款 Landing Page）
            if (isBlacklistedUrl(rootUrl)) {
                return null;
            }
            // 設定 User-Agent 偽裝成瀏覽器
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

            Document doc = Jsoup.connect(rootUrl)
                    .userAgent(userAgent)
                    .timeout(5000)
                    .ignoreHttpErrors(true)
                    .get();

            String title = doc.title();
            if (title == null || title.isEmpty()) title = rootUrl;

            // extract meta description or og:description as snippet, fallback to body prefix
            String snippet = doc.select("meta[name=description]").attr("content");
            if (snippet == null || snippet.isEmpty()) {
                snippet = doc.select("meta[property=og:description]").attr("content");
            }
            if (snippet == null || snippet.isEmpty()) {
                String body = doc.body() == null ? "" : doc.body().text();
                snippet = body.length() > 300 ? body.substring(0, 300) : body;
            }
            if (snippet == null) snippet = "";

            WebNode rootNode = new WebNode(rootUrl, title);
            rootNode.snippet = Normalizer.normalize(snippet, Normalizer.Form.NFKC).toLowerCase();
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

                // 也跳過已知黑名單的子連結（例如 Wikimedia 捐款 landing page、PDF、社群站等）
                if (isBlacklistedUrl(subUrl)) continue;

                try {
                    Document subDoc = Jsoup.connect(subUrl)
                            .userAgent(userAgent)
                            .timeout(3000)
                            .ignoreHttpErrors(true)
                            .get();

                    WebNode childNode = new WebNode(subUrl, subDoc.title());
                    // child snippet
                    String cSnippet = subDoc.select("meta[name=description]").attr("content");
                    if (cSnippet == null || cSnippet.isEmpty()) cSnippet = subDoc.select("meta[property=og:description]").attr("content");
                    if (cSnippet == null || cSnippet.isEmpty()) {
                        String cbody = subDoc.body() == null ? "" : subDoc.body().text();
                        cSnippet = cbody.length() > 300 ? cbody.substring(0, 300) : cbody;
                    }
                    childNode.snippet = Normalizer.normalize(cSnippet == null ? "" : cSnippet, Normalizer.Form.NFKC).toLowerCase();
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

    // 保守的黑名單判斷：匹配 Wikimedia 捐款 landing page，以及其他明顯不需要的路徑
    private boolean isBlacklistedUrl(String url) {
        if (url == null) return true;
        String u = url.toLowerCase();
        // 常見 Wikimedia 捐款 landing page 範例：
        // https://donate.wikimedia.org/w/index.php?title=Special:LandingPage&...&wmf_campaign=...
        if (u.contains("donate.wikimedia.org")
                || u.contains("special:landingpage")
                || u.contains("wmf_campaign")
                || (u.contains("wikimedia") && u.contains("donate"))) {
            return true;
        }

        // IMDb helper / contribute pages often aren't useful for reviews/results
        if (u.contains("help.imdb.com/imdb") || u.contains("contribute.imdb.com/czone")) return true;

        // 另外排除明顯不想要的 host/paths
        if (u.endsWith(".pdf") || u.contains("facebook.com") || u.contains("youtube.com")) return true;

        return false;
    }

    // Stage 1 Helper: 計算關鍵字次數（Boyer-Moore-Horspool 實作，並做 Unicode 正規化）
    private void countKeywords(WebNode node, String text, ArrayList<String> keywords) {
        String lowerText = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase();
        for (String k : keywords) {
            String pat = k == null ? "" : Normalizer.normalize(k, Normalizer.Form.NFKC).toLowerCase().trim();
            int count = 0;
            if (pat.length() == 0) {
                node.keywordCounts.put(k, 0);
                continue;
            }

            int n = lowerText.length();
            int m = pat.length();
            if (n < m) {
                node.keywordCounts.put(k, 0);
                continue;
            }

            int[] shift = buildBmhShift(pat);
            int i = 0;
            while (i <= n - m) {
                int j = m - 1;
                while (j >= 0 && lowerText.charAt(i + j) == pat.charAt(j)) {
                    j--;
                }
                if (j < 0) {
                    count++;
                    // 跳過整個模式以避免重疊匹配（與先前行為一致）
                    i += m;
                } else {
                    char c = lowerText.charAt(i + m - 1);
                    int shiftBy = shift[c];
                    if (shiftBy <= 0) shiftBy = 1;
                    i += shiftBy;
                }
            }

            node.keywordCounts.put(k, count);
        }
    }

    // 建立 BMH 移位表（支援 BMP 範圍）
    private int[] buildBmhShift(String pattern) {
        final int ALPH = 65536; // Java char 的範圍
        int m = pattern.length();
        int[] shift = new int[ALPH];
        Arrays.fill(shift, m);
        for (int i = 0; i < m - 1; i++) {
            shift[pattern.charAt(i)] = m - 1 - i;
        }
        return shift;
    }

    // Stage 4: 語意分析 (從結果中找高頻字)
    public List<RelatedKeyword> deriveRelatedKeywords(List<WebTree> rankedSites) {
        // TF-IDF implementation over top N documents (title + snippet + child titles/snippets)
        int topDocs = Math.min(5, rankedSites == null ? 0 : rankedSites.size());
        if (topDocs == 0) return List.of();

        // Collect documents: title + snippet + child title/snippet
        List<String> docs = new ArrayList<>();
        for (int i = 0; i < topDocs; i++) {
            WebTree tree = rankedSites.get(i);
            if (tree == null || tree.root == null) {
                docs.add("");
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (tree.root.title != null) sb.append(tree.root.title).append(' ');
            if (tree.root.snippet != null) sb.append(tree.root.snippet).append(' ');
            if (tree.root.children != null) {
                for (var c : tree.root.children) {
                    if (c.title != null) sb.append(c.title).append(' ');
                    if (c.snippet != null) sb.append(c.snippet).append(' ');
                }
            }
            docs.add(Normalizer.normalize(sb.toString(), Normalizer.Form.NFKC).toLowerCase());
        }

        // Tokenize documents and compute term frequencies and document frequencies
        List<Map<String, Integer>> docTermFreqs = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();

    Set<String> stopWords = Set.of(
        "movie", "review", "reviews", "film", "imdb", "login", "search", "menu", "watch", "trailer",
        "the", "and", "of", "rating", "cast",
        // common small stopwords
        "to", "re", "a", "an", "in", "on", "for", "by", "with", "from", "this", "that", "is", "are", "be", "as", "at", "it", "its", "you"
    );

        for (String doc : docs) {
            Map<String, Integer> tf = new HashMap<>();
            List<String> tokens = tokenize(doc);
            // count tf
            for (String t : tokens) {
                if (t == null || t.isBlank()) continue;
                if (stopWords.contains(t)) continue;
                // filter numeric-only tokens
                if (t.matches("^\\d+$")) continue;
                tf.put(t, tf.getOrDefault(t, 0) + 1);
            }
            // update df
            for (String term : tf.keySet()) {
                df.put(term, df.getOrDefault(term, 0) + 1);
            }
            docTermFreqs.add(tf);
        }

        int N = docTermFreqs.size();
        Map<String, Double> score = new HashMap<>();

        for (int i = 0; i < N; i++) {
            Map<String, Integer> tf = docTermFreqs.get(i);
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String term = e.getKey();
                double termFreq = e.getValue();
                int docCount = df.getOrDefault(term, 0);
                double idf = Math.log((double)(N + 1) / (docCount + 1)) + 1.0; // smooth idf
                double tfidf = termFreq * idf;
                score.put(term, score.getOrDefault(term, 0.0) + tfidf);
            }
        }

    // sort terms by score and map to RelatedKeyword objects
    List<RelatedKeyword> ranked = score.entrySet().stream()
        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
        .map(e -> new RelatedKeyword(e.getKey(), e.getValue(), df.getOrDefault(e.getKey(), 0)))
        // filter out single ASCII letters which are usually noise (e.g., 'e','o')
        .filter(rk -> rk.term != null && !(rk.term.length() == 1 && rk.term.matches("^[a-z0-9]$")))
        .limit(10)
        .collect(Collectors.toList());

    return ranked.subList(0, Math.min(5, ranked.size()));
    }

    // Simple heuristic tokenizer: detect CJK and use bigrams/unigrams, otherwise Latin tokenization
    private List<String> tokenize(String text) {
        if (text == null) return List.of();
        // Quick check for presence of CJK characters
        if (looksLikeCJK(text)) {
            return tokenizeCJK(text);
        } else {
            return tokenizeLatin(text);
        }
    }

    private boolean looksLikeCJK(String s) {
        int cnt = 0;
        int limit = Math.min(s.length(), 200);
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
            if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || ub == Character.UnicodeBlock.HIRAGANA
                    || ub == Character.UnicodeBlock.KATAKANA
                    || ub == Character.UnicodeBlock.HANGUL_SYLLABLES) {
                cnt++;
            }
        }
        return cnt >= 1; // if any CJK char present, treat as CJK document
    }

    private List<String> tokenizeCJK(String s) {
        // For mixed CJK+Latin text, keep Latin words together and only split CJK into uni/bi-grams.
        String cleaned = s.replaceAll("\\p{Punct}", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        List<String> tokens = new ArrayList<>();

        StringBuilder latinBuf = new StringBuilder();
        // collect CJK chars positions for bigrams
        StringBuilder cjkOnly = new StringBuilder();

        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (isCJKChar(c)) {
                // flush latin buffer
                if (latinBuf.length() > 0) {
                    String lat = latinBuf.toString().toLowerCase();
                    tokens.addAll(tokenizeLatin(lat));
                    latinBuf.setLength(0);
                }
                tokens.add(String.valueOf(c));
                cjkOnly.append(c);
            } else {
                latinBuf.append(c);
                // keep placeholder in cjkOnly to preserve alignment
                cjkOnly.append(' ');
            }
        }
        if (latinBuf.length() > 0) {
            tokens.addAll(tokenizeLatin(latinBuf.toString().toLowerCase()));
        }

        // add bigrams only for consecutive CJK characters
        for (int i = 0; i < cjkOnly.length() - 1; i++) {
            char a = cjkOnly.charAt(i);
            char b = cjkOnly.charAt(i + 1);
            if (a != ' ' && b != ' ') {
                tokens.add(cjkOnly.substring(i, i + 2));
            }
        }

        return tokens;
    }

    private boolean isCJKChar(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || ub == Character.UnicodeBlock.HIRAGANA
                || ub == Character.UnicodeBlock.KATAKANA
                || ub == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9]+");
    private List<String> tokenizeLatin(String s) {
        List<String> tokens = new ArrayList<>();
        var m = LATIN_TOKEN.matcher(s);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }
}
