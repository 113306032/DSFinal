package com.example.ds_final.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ds_final.model.WebTree;

@Controller
public class SearchController {

    @Autowired
    private SearchService searchService;

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/search")
    public String search(@RequestParam String query, Model model) {
        System.out.println("User searching: " + query);
        
        // 準備關鍵字清單：把整句與有意義的 token 都加入（方便頁面只出現片名一部分也能命中）
        ArrayList<String> keywords = new ArrayList<>();
        if (query != null) {
            String q = query.trim().toLowerCase();
            if (!q.isEmpty()) {
                // 完整片語
                keywords.add(q);
                // 依非字元數分割成 token（保留中文/英文/數字）
                String[] parts = q.split("[^\\p{L}\\p{N}]+");
                boolean anyLong = false;
                StringBuilder joined = new StringBuilder();
                for (String p : parts) {
                    if (p.length() > 1) anyLong = true;
                    if (p.length() > 1 && !keywords.contains(p)) {
                        keywords.add(p);
                    }
                    joined.append(p);
                }
                // 處理使用者輸入像「天 空 之 城」這類每個 token 為單字的情況，加入無空格版本
                String joinedNoSpace = joined.toString();
                if (!anyLong && joinedNoSpace.length() > 1 && !keywords.contains(joinedNoSpace)) {
                    keywords.add(joinedNoSpace);
                }
            }
        }
        // 一些固定的電影相關字詞
        keywords.add("movie");
        keywords.add("review");
        keywords.add("cast");

        // 1. Google 搜尋
        List<String> potentialUrls = searchService.googleSearch(query);

        // 2. 爬蟲 + 建樹 + 評分
        List<WebTree> results = new ArrayList<>();
        for (String url : potentialUrls) {
            // 過濾顯而易見的非網頁連結或不想要的來源（例如社群或捐款頁）
            if (url.endsWith(".pdf")
                    || url.contains("facebook.com")
                    || url.contains("youtube.com")
                    || url.contains("donate.wikimedia.org")
                    || url.contains("Special:LandingPage")
                    || url.contains("wmf_campaign")) continue;

            WebTree tree = searchService.processSite(url, keywords);
            
            // 過濾邏輯：只留下一點分數的網站 (避免完全無關的論壇)
            if (tree != null && tree.siteScore > 5) {
                // --- 標題清理 (Clean Title) ---
                String cleanTitle = tree.root.title;
                cleanTitle = cleanTitle.replaceAll("(?i) - IMDb.*", "")
                                       .replaceAll("(?i) - Rotten Tomatoes.*", "")
                                       .replaceAll("(?i) \\|.*", "")
                                       .replaceAll("(?i) - Wikipedia", "");
                tree.root.title = cleanTitle;
                // ---------------------------
                
                results.add(tree);
            }
        }

        // 3. 排序 (分數高到低)
        results.sort(Comparator.comparingDouble((WebTree t) -> t.siteScore).reversed());

        // 4. 語意分析推薦
        List<String> related = searchService.deriveRelatedKeywords(results);

        model.addAttribute("query", query);
        model.addAttribute("results", results);
        model.addAttribute("relatedKeywords", related);

        return "result";
    }
}