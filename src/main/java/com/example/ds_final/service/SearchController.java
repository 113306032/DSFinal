package com.example.ds_final.service;

import com.example.ds_final.model.WebTree;
import com.example.ds_final.service.SearchService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        
        // 準備關鍵字清單 (全部轉小寫方便比對)
        ArrayList<String> keywords = new ArrayList<>();
        keywords.add(query.toLowerCase());
        keywords.add("movie");
        keywords.add("review");
        keywords.add("cast");

        // 1. Google 搜尋
        List<String> potentialUrls = searchService.googleSearch(query);

        // 2. 爬蟲 + 建樹 + 評分
        List<WebTree> results = new ArrayList<>();
        for (String url : potentialUrls) {
            // 過濾顯而易見的非網頁連結
            if (url.endsWith(".pdf") || url.contains("facebook.com") || url.contains("youtube.com")) continue;

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