package com.automation.report;

import com.automation.model.ComparisonResult;
import com.automation.model.ComparisonResult.*;
import com.automation.model.LinkData;
import com.automation.model.SiteData;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Margin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ReportFormatter {

    private final Browser browser;

    public ReportFormatter(Browser browser) {
        this.browser = browser;
    }

    public void generateReport(ComparisonResult result) {
        String html = buildHtml(result);

        Path reportsDir = Paths.get("reports");
        try {
            if (!Files.exists(reportsDir)) {
                Files.createDirectories(reportsDir);
            }
        } catch (IOException e) {
            System.out.println("[WARN] Failed to create reports directory: " + e.getMessage());
            reportsDir = Paths.get(".");
        }
        
        String baseFilename = buildFilename(result.siteA().getUrl(), result.siteB().getUrl());
        Path htmlPath = reportsDir.resolve(baseFilename + ".html");
        Path pdfPath = reportsDir.resolve(baseFilename + ".pdf");
        Path csvPath = reportsDir.resolve(baseFilename + ".csv");

        try {
            Files.writeString(htmlPath, html);
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to write HTML: " + e.getMessage());
            return;
        }
        
        generateCsv(result, csvPath);

        try {
            Page page = browser.newPage();
            page.navigate("file:///" + htmlPath.toAbsolutePath().toString().replace("\\", "/"));
            page.waitForLoadState();
            page.pdf(new Page.PdfOptions()
                    .setPath(pdfPath)
                    .setPrintBackground(true)
                    .setFormat("A4")
                    .setMargin(new Margin()
                            .setTop("18mm").setBottom("18mm")
                            .setLeft("16mm").setRight("16mm")));
            page.close();
            System.out.println("[INFO] Reports saved: " + pdfPath.toAbsolutePath());
        } catch (Exception e) {
            System.out.println("[ERROR] PDF generation failed: " + e.getMessage());
        }
    }

    private void generateCsv(ComparisonResult result, Path csvPath) {
        StringBuilder csv = new StringBuilder();
        csv.append("Check,Site A,Site B,Result\n");
        csv.append("Metadata,").append(result.siteA().getMetadata().size()).append(",").append(result.siteB().getMetadata().size()).append(",").append(result.metadataDiff().matches() ? "PASS" : "MISMATCH").append("\n");
        csv.append("DataLayer,").append(result.siteA().getDataLayer().size()).append(",").append(result.siteB().getDataLayer().size()).append(",").append(result.dataLayerDiff().matches() ? "PASS" : "MISMATCH").append("\n");
        csv.append("Text,").append(result.textDiff().totalLinesA()).append(",").append(result.textDiff().totalLinesB()).append(",").append(result.textDiff().matches() ? "PASS" : "MISMATCH").append("\n");
        csv.append("Images,").append(result.siteA().getImages().size()).append(",").append(result.siteB().getImages().size()).append(",").append(result.imageDiff().matches() ? "PASS" : "MISMATCH").append("\n");
        csv.append("Links,").append(result.siteA().getLinks().size()).append(",").append(result.siteB().getLinks().size()).append(",").append(result.linkDiff().matches() ? "PASS" : "MISMATCH").append("\n");
        try {
            Files.writeString(csvPath, csv.toString());
        } catch (IOException e) {
            System.out.println("[WARN] Failed to write CSV: " + e.getMessage());
        }
    }

    private String buildHtml(ComparisonResult result) {
        SiteData a = result.siteA();
        SiteData b = result.siteB();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        sb.append("<title>Content Comparison Report</title>");
        sb.append("<style>").append(css()).append("</style></head><body>");

        // ── Report Header
        sb.append("<div class='page-header'>");
        sb.append("<div class='report-title'>Content Comparison Report</div>");
        sb.append("<table class='header-info'><tbody>");
        sb.append("<tr><td class='info-label'>Generated</td><td>").append(ts).append("</td></tr>");
        sb.append("<tr><td class='info-label'>Site A</td><td>").append(esc(a.getUrl())).append("</td></tr>");
        sb.append("<tr><td class='info-label'>Site B</td><td>").append(esc(b.getUrl())).append("</td></tr>");
        sb.append("<tr><td class='info-label'>Performance</td><td>")
          .append("Extraction A: ").append(a.getExtractionTimeMillis()).append("ms, ")
          .append("Extraction B: ").append(b.getExtractionTimeMillis()).append("ms, ")
          .append("Comparison: ").append(result.comparisonTimeMillis()).append("ms")
          .append("</td></tr>");
        sb.append("</tbody></table>");
        sb.append("</div>");

        // ── Summary Table
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>Summary</div>");
        sb.append("<table><thead><tr><th>Check</th><th>Site A</th><th>Site B</th><th>Result</th></tr></thead><tbody>");
        summaryRow(sb, "Metadata",  a.getMetadata().size() + " tag(s)",  b.getMetadata().size() + " tag(s)",  result.metadataDiff().matches());
        summaryRow(sb, "DataLayer", a.getDataLayer().size() + " property(ies)", b.getDataLayer().size() + " property(ies)", result.dataLayerDiff().matches());
        summaryRow(sb, "Text",      result.textDiff().totalLinesA() + " text node(s)", result.textDiff().totalLinesB() + " text node(s)", result.textDiff().matches());
        summaryRow(sb, "Images",    a.getImages().size() + " image(s)",  b.getImages().size() + " image(s)",   result.imageDiff().matches());
        summaryRow(sb, "Links",     a.getLinks().size() + " link(s)",    b.getLinks().size() + " link(s)",     result.linkDiff().matches());
        sb.append("</tbody></table>");

        sb.append("<div class='final-result ").append(result.isAllMatch() ? "final-pass" : "final-fail").append("'>");
        sb.append("Overall: ").append(result.isAllMatch() ? "PASS" : "MISMATCH");
        sb.append("</div>");
        sb.append("</div>");

        // ── Section 1: Metadata
        sb.append(renderMapDiff("1. Metadata", result.metadataDiff(), a.getMetadata(), b.getMetadata()));

        // ── Section 2: DataLayer
        sb.append(renderMapDiff("2. Site Data Layer", result.dataLayerDiff(), a.getDataLayer(), b.getDataLayer()));

        // ── Section 3: Text
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>3. Text Content <span class='result-inline ")
          .append(result.textDiff().matches() ? "pass" : "fail").append("'>")
          .append(result.textDiff().matches() ? "PASS" : "MISMATCH").append("</span></div>");

        sb.append("<table class='stat-table'><tbody>");
        sb.append("<tr><td class='stat-label'>Matched</td><td>").append(result.textDiff().matchedLineCount()).append(" text node(s)</td></tr>");
        sb.append("<tr><td class='stat-label'>Differences</td><td>")
          .append(result.textDiff().onlyInA().size() + result.textDiff().onlyInB().size()).append(" text node(s)</td></tr>");
        sb.append("</tbody></table>");

        if (result.textDiff().matches()) {
            sb.append("<p class='note'>Content is identical. Showing first 60 text nodes from each site.</p>");
            sb.append("<table><thead><tr><th style='width:50%'>Site A Text</th><th style='width:50%'>Site B Text</th></tr></thead><tbody>");
            String[] textLinesA = a.getRawText().split("\\n");
            String[] textLinesB = b.getRawText().split("\\n");
            int maxLines = Math.min(Math.max(textLinesA.length, textLinesB.length), 60);
            for (int i = 0; i < maxLines; i++) {
                String la = (i < textLinesA.length) ? textLinesA[i].trim() : "";
                String lb = (i < textLinesB.length) ? textLinesB[i].trim() : "";
                if (la.isEmpty() && lb.isEmpty()) continue;
                sb.append("<tr><td>").append(esc(la)).append("</td><td>").append(esc(lb)).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        } else {
            sb.append("<table><thead><tr><th>Site</th><th>Text Node Content</th></tr></thead><tbody>");
            int countA = 0;
            for (String line : result.textDiff().onlyInA()) {
                if (line.isBlank()) continue;
                if (countA++ > 50) {
                    sb.append("<tr class='row-fail'><td class='status-cell fail'>...</td><td><i>and " + (result.textDiff().onlyInA().size() - 50) + " more only in A</i></td></tr>");
                    break;
                }
                sb.append("<tr class='row-fail'><td class='status-cell fail' style='white-space:nowrap'>Only in A</td>")
                  .append("<td>").append(esc(line)).append("</td></tr>");
            }
            int countB = 0;
            for (String line : result.textDiff().onlyInB()) {
                if (line.isBlank()) continue;
                if (countB++ > 50) {
                    sb.append("<tr class='row-fail'><td class='status-cell fail'>...</td><td><i>and " + (result.textDiff().onlyInB().size() - 50) + " more only in B</i></td></tr>");
                    break;
                }
                sb.append("<tr class='row-fail'><td class='status-cell fail' style='white-space:nowrap'>Only in B</td>")
                  .append("<td>").append(esc(line)).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</div>");

        // ── Section 4: Images
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>4. Images <span class='result-inline ")
          .append(result.imageDiff().matches() ? "pass" : "fail").append("'>")
          .append(result.imageDiff().matches() ? "PASS" : "MISMATCH").append("</span></div>");

        sb.append("<table class='stat-table'><tbody>");
        sb.append("<tr><td class='stat-label'>Matched</td><td>").append(result.imageDiff().matchedImagesCount()).append("</td></tr>");
        if (result.imageDiff().visualMatchImagesCount() > 0) {
            sb.append("<tr><td class='stat-label'>Visual Match</td><td class='status-cell warn'>").append(result.imageDiff().visualMatchImagesCount()).append(" (same image, different path/format)</td></tr>");
        }
        sb.append("<tr><td class='stat-label'>Mismatched</td><td>").append(result.imageDiff().mismatchCount()).append("</td></tr>");
        sb.append("</tbody></table>");

        sb.append("<table><thead><tr><th style='width:31%'>Site A Source</th><th style='width:10%'>Site A Hash</th><th style='width:31%'>Site B Source</th><th style='width:10%'>Site B Hash</th><th style='width:14%'>Status</th></tr></thead><tbody>");
        
        for (ImageMatch match : result.imageDiff().matchesList()) {
            boolean isPass = match.status().equals("MATCH") || match.status().equals("VISUAL MATCH");
            String classStatus = match.status().equals("MATCH") ? "pass" : (match.status().equals("VISUAL MATCH") ? "warn" : "fail");
            sb.append("<tr").append(isPass ? "" : " class='row-fail'").append(">");
            sb.append("<td class='src-cell'>").append(match.imgA() != null ? esc(match.imgA().getSrc()) : "<em class='na'>N/A</em>").append("</td>");
            sb.append("<td class='hash-cell'>").append(match.imgA() != null ? match.imgA().getHash().substring(0, 8) + "..." : "N/A").append("</td>");
            sb.append("<td class='src-cell'>").append(match.imgB() != null ? esc(match.imgB().getSrc()) : "<em class='na'>N/A</em>").append("</td>");
            sb.append("<td class='hash-cell'>").append(match.imgB() != null ? match.imgB().getHash().substring(0, 8) + "..." : "N/A").append("</td>");
            sb.append("<td class='status-cell ").append(classStatus).append("'>").append(match.status()).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");

        // ── Section 5: Links
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>5. Links <span class='result-inline ")
          .append(result.linkDiff().matches() ? "pass" : "fail").append("'>")
          .append(result.linkDiff().matches() ? "PASS" : "MISMATCH").append("</span></div>");

        sb.append("<table class='stat-table'><tbody>");
        sb.append("<tr><td class='stat-label'>Matched</td><td>").append(result.linkDiff().matchedLinksCount()).append("</td></tr>");
        sb.append("<tr><td class='stat-label'>Only in A</td><td>").append(result.linkDiff().onlyInA().size()).append("</td></tr>");
        sb.append("<tr><td class='stat-label'>Only in B</td><td>").append(result.linkDiff().onlyInB().size()).append("</td></tr>");
        sb.append("</tbody></table>");

        Map<String, LinkData> slugToLinkA = new LinkedHashMap<>();
        for (LinkData l : a.getLinks()) slugToLinkA.put(l.getSlug(), l);
        Map<String, LinkData> slugToLinkB = new LinkedHashMap<>();
        for (LinkData l : b.getLinks()) slugToLinkB.put(l.getSlug(), l);
        Set<String> allSlugs = new TreeSet<>();
        allSlugs.addAll(slugToLinkA.keySet());
        allSlugs.addAll(slugToLinkB.keySet());

        sb.append("<table><thead><tr><th style='width:20%'>Link Text</th><th style='width:20%'>Slug</th><th style='width:25%'>Site A Href</th><th style='width:25%'>Site B Href</th><th style='width:10%'>Status</th></tr></thead><tbody>");
        for (String slug : allSlugs) {
            LinkData lA = slugToLinkA.get(slug);
            LinkData lB = slugToLinkB.get(slug);
            String hA = lA != null ? lA.getOriginalHref() : null;
            String hB = lB != null ? lB.getOriginalHref() : null;
            String linkText = lA != null ? lA.getLinkText() : (lB != null ? lB.getLinkText() : "");
            boolean match = hA != null && hB != null;
            sb.append("<tr").append(match ? "" : " class='row-fail'").append(">");
            sb.append("<td class='src-cell'>").append(esc(linkText)).append("</td>");
            sb.append("<td class='hash-cell'>").append(esc(slug)).append("</td>");
            sb.append("<td class='src-cell'>").append(hA != null ? esc(hA) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td class='src-cell'>").append(hB != null ? esc(hB) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td class='status-cell ").append(match ? "pass" : "fail").append("'>")
              .append(match ? "MATCH" : (hA == null ? "ONLY IN B" : "ONLY IN A")).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private String renderMapDiff(String title, MapDiff diff, Map<String, String> mapA, Map<String, String> mapB) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>").append(title).append(" <span class='result-inline ")
          .append(diff.matches() ? "pass" : "fail").append("'>")
          .append(diff.matches() ? "PASS" : "MISMATCH").append("</span></div>");
        sb.append("<table><thead><tr><th>Key</th><th>Site A Value</th><th>Site B Value</th><th>Status</th></tr></thead><tbody>");

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(mapA.keySet());
        allKeys.addAll(mapB.keySet());

        for (String key : allKeys) {
            String valA = mapA.get(key);
            String valB = mapB.get(key);
            boolean isDiff = !Objects.equals(valA, valB);
            sb.append("<tr").append(isDiff ? " class='row-fail'" : "").append(">");
            sb.append("<td class='key-cell'>").append(esc(key)).append("</td>");
            sb.append("<td>").append(valA != null ? esc(valA) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td>").append(valB != null ? esc(valB) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td class='status-cell ").append(isDiff ? "fail" : "pass").append("'>")
              .append(isDiff ? (valA == null ? "MISSING IN A" : valB == null ? "MISSING IN B" : "DIFF") : "MATCH")
              .append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    private void summaryRow(StringBuilder sb, String check, String valA, String valB, boolean pass) {
        sb.append("<tr>");
        sb.append("<td class='key-cell'>").append(check).append("</td>");
        sb.append("<td>").append(valA).append("</td>");
        sb.append("<td>").append(valB).append("</td>");
        sb.append("<td class='status-cell ").append(pass ? "pass" : "fail").append("'>")
          .append(pass ? "PASS" : "MISMATCH").append("</td>");
        sb.append("</tr>");
    }

    private String buildFilename(String urlA, String urlB) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String hash = "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest((urlA + urlB).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", bytes[i]));
            hash = "_" + sb.toString();
        } catch (Exception e) {}
        return extractDomain(urlA) + "_vs_" + extractDomain(urlB) + hash + "_" + ts;
    }

    private String extractDomain(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null ? host.replaceAll("[^a-zA-Z0-9._-]", "_") : "unknown";
        } catch (Exception e) { return "unknown"; }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String css() {
        try (InputStream is = getClass().getResourceAsStream("/report.css")) {
            if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {}
        return "";
    }
}
