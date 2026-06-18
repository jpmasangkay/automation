package com.automation.report;

import com.automation.model.ComparisonResult;
import com.automation.model.LinkData;
import com.automation.model.SiteData;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Margin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ReportFormatter {

    public void generateReport(ComparisonResult result) {
        String html = buildHtml(result);

        Path tempHtml = null;
        try {
            tempHtml = Files.createTempFile("comparator_", ".html");
            Files.writeString(tempHtml, html);
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to write temp HTML: " + e.getMessage());
            return;
        }

        Path reportsDir = Paths.get("reports");
        try {
            if (!Files.exists(reportsDir)) {
                Files.createDirectories(reportsDir);
            }
        } catch (IOException e) {
            System.out.println("[WARN] Failed to create reports directory: " + e.getMessage());
            reportsDir = Paths.get(".");
        }
        Path pdfPath = reportsDir.resolve(buildFilename(result.getSiteA().getUrl(), result.getSiteB().getUrl()));

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate("file:///" + tempHtml.toAbsolutePath().toString().replace("\\", "/"));
            page.waitForLoadState();
            page.pdf(new Page.PdfOptions()
                    .setPath(pdfPath)
                    .setPrintBackground(true)
                    .setFormat("A4")
                    .setMargin(new Margin()
                            .setTop("18mm").setBottom("18mm")
                            .setLeft("16mm").setRight("16mm")));
            browser.close();
            System.out.println("[INFO] Report saved: " + pdfPath.toAbsolutePath());
        } catch (Exception e) {
            System.out.println("[ERROR] PDF generation failed: " + e.getMessage());
        }

        try { Files.deleteIfExists(tempHtml); } catch (IOException ignored) {}
    }

    private String buildHtml(ComparisonResult result) {
        SiteData a = result.getSiteA();
        SiteData b = result.getSiteB();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        sb.append("<title>Content Comparison Report</title>");
        sb.append("<style>").append(css()).append("</style></head><body>");

        // ── Report Header ───────────────────────────────────────────────────
        sb.append("<div class='page-header'>");
        sb.append("<div class='report-title'>Content Comparison Report</div>");
        sb.append("<table class='header-info'><tbody>");
        sb.append("<tr><td class='info-label'>Generated</td><td>").append(ts).append("</td></tr>");
        sb.append("<tr><td class='info-label'>Site A</td><td>").append(esc(a.getUrl())).append("</td></tr>");
        sb.append("<tr><td class='info-label'>Site B</td><td>").append(esc(b.getUrl())).append("</td></tr>");
        sb.append("</tbody></table>");
        sb.append("</div>");

        // ── Summary Table ────────────────────────────────────────────────────
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>Summary</div>");
        sb.append("<table><thead><tr><th>Check</th><th>Site A</th><th>Site B</th><th>Result</th></tr></thead><tbody>");
        summaryRow(sb, "Metadata",  a.getMetadata().size() + " tag(s)",  b.getMetadata().size() + " tag(s)",  result.isMetadataMatches());
        summaryRow(sb, "DataLayer", a.getDataLayer().size() + " property(ies)", b.getDataLayer().size() + " property(ies)", result.isDataLayerMatches());
        summaryRow(sb, "Text",      a.getRawText().split("\\n").length + " line(s)", b.getRawText().split("\\n").length + " line(s)", result.isTextMatches());
        summaryRow(sb, "Images",    a.getImages().size() + " image(s)",  b.getImages().size() + " image(s)",   result.isImagesMatch());
        summaryRow(sb, "Links",     a.getLinks().size() + " link(s)",    b.getLinks().size() + " link(s)",     result.isLinksMatch());
        sb.append("</tbody></table>");

        sb.append("<div class='final-result ").append(result.isAllMatch() ? "final-pass" : "final-fail").append("'>");
        sb.append("Overall: ").append(result.isAllMatch() ? "PASS" : "MISMATCH");
        sb.append("</div>");
        sb.append("</div>");

        // ── Section 1: Metadata ──────────────────────────────────────────────
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>1. Metadata <span class='result-inline ")
          .append(result.isMetadataMatches() ? "pass" : "fail").append("'>")
          .append(result.isMetadataMatches() ? "PASS" : "MISMATCH").append("</span></div>");
        sb.append("<table><thead><tr><th>Key</th><th>Site A Value</th><th>Site B Value</th><th>Status</th></tr></thead><tbody>");

        Set<String> allMetaKeys = new LinkedHashSet<>();
        allMetaKeys.addAll(a.getMetadata().keySet());
        allMetaKeys.addAll(b.getMetadata().keySet());

        for (String key : allMetaKeys) {
            String valA = a.getMetadata().getOrDefault(key, null);
            String valB = b.getMetadata().getOrDefault(key, null);
            boolean diff = !Objects.equals(valA, valB);
            sb.append("<tr").append(diff ? " class='row-fail'" : "").append(">");
            sb.append("<td class='key-cell'>").append(esc(key)).append("</td>");
            sb.append("<td>").append(valA != null ? esc(valA) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td>").append(valB != null ? esc(valB) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td class='status-cell ").append(diff ? "fail" : "pass").append("'>")
              .append(diff ? (valA == null ? "MISSING IN A" : valB == null ? "MISSING IN B" : "DIFF") : "MATCH")
              .append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");

        // ── Section 2: Site Data Layer ───────────────────────────────────────
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>2. Site Data Layer <span class='result-inline ")
          .append(result.isDataLayerMatches() ? "pass" : "fail").append("'>")
          .append(result.isDataLayerMatches() ? "PASS" : "MISMATCH").append("</span></div>");
        sb.append("<table><thead><tr><th>Property</th><th>Site A Value</th><th>Site B Value</th><th>Status</th></tr></thead><tbody>");

        Set<String> allDlKeys = new LinkedHashSet<>();
        allDlKeys.addAll(a.getDataLayer().keySet());
        allDlKeys.addAll(b.getDataLayer().keySet());

        for (String key : allDlKeys) {
            String valA = a.getDataLayer().getOrDefault(key, null);
            String valB = b.getDataLayer().getOrDefault(key, null);
            boolean diff = !Objects.equals(valA, valB);
            sb.append("<tr").append(diff ? " class='row-fail'" : "").append(">");
            sb.append("<td class='key-cell'>").append(esc(key)).append("</td>");
            sb.append("<td>").append(valA != null ? esc(valA) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td>").append(valB != null ? esc(valB) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td class='status-cell ").append(diff ? "fail" : "pass").append("'>")
              .append(diff ? (valA == null ? "MISSING IN A" : valB == null ? "MISSING IN B" : "DIFF") : "MATCH")
              .append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");

        // ── Section 3: Text ──────────────────────────────────────────────────
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>3. Text Content <span class='result-inline ")
          .append(result.isTextMatches() ? "pass" : "fail").append("'>")
          .append(result.isTextMatches() ? "PASS" : "MISMATCH").append("</span></div>");

        sb.append("<table class='stat-table'><tbody>");
        sb.append("<tr><td class='stat-label'>Site A</td><td>")
          .append(result.getTotalLinesA()).append(" unique text node(s), ")
          .append(a.getRawText().split(" ").length).append(" word(s)</td></tr>");
        sb.append("<tr><td class='stat-label'>Site B</td><td>")
          .append(result.getTotalLinesB()).append(" unique text node(s), ")
          .append(b.getRawText().split(" ").length).append(" word(s)</td></tr>");
        sb.append("<tr><td class='stat-label'>Matched</td><td>").append(result.getMatchedLineCount()).append(" text node(s)</td></tr>");
        sb.append("<tr><td class='stat-label'>Differences</td><td>")
          .append(result.getTextOnlyInA().size() + result.getTextOnlyInB().size()).append(" text node(s)</td></tr>");
        sb.append("</tbody></table>");

        if (result.isTextMatches()) {
            // Show full text side by side when matching
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
            // Show the differing lines
            sb.append("<table><thead><tr><th>Site</th><th>Text Node Content</th></tr></thead><tbody>");
            for (String line : result.getTextOnlyInA()) {
                if (line.isBlank()) continue;
                sb.append("<tr class='row-fail'><td class='status-cell fail' style='white-space:nowrap'>Only in A</td>")
                  .append("<td>").append(esc(line)).append("</td></tr>");
            }
            for (String line : result.getTextOnlyInB()) {
                if (line.isBlank()) continue;
                sb.append("<tr class='row-fail'><td class='status-cell fail' style='white-space:nowrap'>Only in B</td>")
                  .append("<td>").append(esc(line)).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</div>");

        // ── Section 4: Images ────────────────────────────────────────────────
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>4. Images <span class='result-inline ")
          .append(result.isImagesMatch() ? "pass" : "fail").append("'>")
          .append(result.isImagesMatch() ? "PASS" : "MISMATCH").append("</span></div>");

        sb.append("<table class='stat-table'><tbody>");
        sb.append("<tr><td class='stat-label'>Site A</td><td>").append(a.getImages().size()).append(" image(s)</td></tr>");
        sb.append("<tr><td class='stat-label'>Site B</td><td>").append(b.getImages().size()).append(" image(s)</td></tr>");
        sb.append("<tr><td class='stat-label'>Matched</td><td>").append(result.getMatchedImagesCount()).append("</td></tr>");
        if (result.getVisualMatchImagesCount() > 0) {
            sb.append("<tr><td class='stat-label'>Visual Match</td><td class='status-cell warn'>").append(result.getVisualMatchImagesCount()).append(" (same image, different path/format)</td></tr>");
        }
        sb.append("<tr><td class='stat-label'>Mismatched</td><td>").append(result.getMismatchedImageIndices().size()).append("</td></tr>");
        sb.append("</tbody></table>");

        sb.append("<table><thead><tr><th style='width:4%'>#</th><th style='width:31%'>Site A Source</th><th style='width:10%'>Site A Hash</th><th style='width:31%'>Site B Source</th><th style='width:10%'>Site B Hash</th><th style='width:14%'>Status</th></tr></thead><tbody>");
        int imgTotal = Math.max(a.getImages().size(), b.getImages().size());
        for (int i = 0; i < imgTotal; i++) {
            boolean hasA = i < a.getImages().size();
            boolean hasB = i < b.getImages().size();
            
            String statusText;
            String statusClass;
            if (hasA && hasB) {
                boolean hashMatch = a.getImages().get(i).getHash().equals(b.getImages().get(i).getHash());
                if (hashMatch) {
                    statusText = "MATCH";
                    statusClass = "pass";
                } else {
                    // Check perceptual hash — visually identical but different binary
                    String phA = a.getImages().get(i).getPerceptualHash();
                    String phB = b.getImages().get(i).getPerceptualHash();
                    boolean perceptualMatch = !"phash-error".equals(phA)
                            && !"phash-error".equals(phB)
                            && phA.equals(phB);
                    if (perceptualMatch) {
                        statusText = "VISUAL MATCH";
                        statusClass = "warn";
                    } else {
                        boolean srcMatch = a.getImages().get(i).getSrc().equals(b.getImages().get(i).getSrc());
                        statusText = srcMatch ? "HASH DIFF" : "DIFF";
                        statusClass = "fail";
                    }
                }
            } else if (!hasA) {
                statusText = "MISSING IN A";
                statusClass = "fail";
            } else {
                statusText = "MISSING IN B";
                statusClass = "fail";
            }

            boolean isPass = statusClass.equals("pass") || statusClass.equals("warn");
            sb.append("<tr").append(isPass ? "" : " class='row-fail'").append(">");
            sb.append("<td class='num-cell'>").append(i).append("</td>");
            sb.append("<td class='src-cell'>").append(hasA ? esc(a.getImages().get(i).getSrc()) : "<em class='na'>N/A</em>").append("</td>");
            sb.append("<td class='hash-cell'>").append(hasA ? a.getImages().get(i).getHash().substring(0, 8) + "..." : "N/A").append("</td>");
            sb.append("<td class='src-cell'>").append(hasB ? esc(b.getImages().get(i).getSrc()) : "<em class='na'>N/A</em>").append("</td>");
            sb.append("<td class='hash-cell'>").append(hasB ? b.getImages().get(i).getHash().substring(0, 8) + "..." : "N/A").append("</td>");
            sb.append("<td class='status-cell ").append(statusClass).append("'>").append(statusText).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");

        // ── Section 5: Links ─────────────────────────────────────────────────
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>5. Links <span class='result-inline ")
          .append(result.isLinksMatch() ? "pass" : "fail").append("'>")
          .append(result.isLinksMatch() ? "PASS" : "MISMATCH").append("</span></div>");

        sb.append("<table class='stat-table'><tbody>");
        sb.append("<tr><td class='stat-label'>Site A</td><td>").append(a.getLinks().size()).append(" unique link(s)</td></tr>");
        sb.append("<tr><td class='stat-label'>Site B</td><td>").append(b.getLinks().size()).append(" unique link(s)</td></tr>");
        sb.append("<tr><td class='stat-label'>Matched</td><td>").append(result.getMatchedLinksCount()).append("</td></tr>");
        sb.append("<tr><td class='stat-label'>Only in A</td><td>").append(result.getLinksOnlyInA().size()).append("</td></tr>");
        sb.append("<tr><td class='stat-label'>Only in B</td><td>").append(result.getLinksOnlyInB().size()).append("</td></tr>");
        sb.append("</tbody></table>");

        // Full links table
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
        return extractDomain(urlA) + "_vs_" + extractDomain(urlB) + "_" + ts + ".pdf";
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
        return """
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body {
                font-family: Arial, Helvetica, sans-serif;
                font-size: 9.5pt;
                color: #111;
                background: #fff;
                line-height: 1.45;
            }

            /* ── Header ─────────────────────────────────────────────────── */
            .page-header {
                padding: 20px 24px 16px;
                border-bottom: 2px solid #111;
                margin-bottom: 0;
            }
            .report-title {
                font-size: 15pt;
                font-weight: bold;
                text-transform: uppercase;
                letter-spacing: 1.5px;
            }
            .header-info {
                margin-top: 10px;
                border: none;
                width: auto;
            }
            .header-info td {
                border: none;
                padding: 2px 16px 2px 0;
                font-size: 8.5pt;
                color: #444;
                background: transparent;
            }
            .info-label {
                font-weight: bold;
                color: #111 !important;
                white-space: nowrap;
            }

            /* ── Blocks ──────────────────────────────────────────────────── */
            .block {
                padding: 16px 24px 14px;
                border-bottom: 1px solid #d0d0d0;
            }
            .section-title {
                font-size: 10.5pt;
                font-weight: bold;
                text-transform: uppercase;
                letter-spacing: 0.8px;
                margin-bottom: 10px;
                color: #111;
            }
            .result-inline {
                font-size: 8pt;
                font-weight: bold;
                margin-left: 10px;
                letter-spacing: 0.5px;
            }
            .result-inline.pass { color: #1a6e1a; }
            .result-inline.fail { color: #a30000; }

            /* ── Final Result ────────────────────────────────────────────── */
            .final-result {
                margin-top: 10px;
                font-size: 10pt;
                font-weight: bold;
                padding: 6px 12px;
                display: inline-block;
            }
            .final-pass { color: #1a6e1a; border: 1px solid #1a6e1a; background: #f0faf0; }
            .final-fail { color: #a30000; border: 1px solid #a30000; background: #fdf0f0; }

            /* ── Tables ──────────────────────────────────────────────────── */
            table {
                width: 100%;
                table-layout: fixed;
                border-collapse: collapse;
                font-size: 8.5pt;
                margin-top: 8px;
            }
            th {
                background: #efefef;
                color: #111;
                padding: 6px 9px;
                text-align: left;
                font-weight: bold;
                font-size: 7.5pt;
                text-transform: uppercase;
                letter-spacing: 0.3px;
                border-top: 1px solid #aaa;
                border-bottom: 1px solid #aaa;
            }
            td {
                padding: 5px 9px;
                border-bottom: 1px solid #e5e5e5;
                vertical-align: top;
                word-break: break-word;
            }
            tr:nth-child(even) td { background: #fafafa; }
            tr.row-fail td { background: #fff4f4 !important; }

            /* ── Stat table ──────────────────────────────────────────────── */
            .stat-table { width: auto; margin-bottom: 10px; }
            .stat-table td { border: none; padding: 2px 16px 2px 0; }
            .stat-label { font-weight: bold; white-space: nowrap; color: #444; }

            /* ── Status cells ────────────────────────────────────────────── */
            .status-cell { font-weight: bold; white-space: nowrap; }
            .status-cell.pass { color: #1a6e1a; }
            .status-cell.warn { color: #8a6000; }
            .status-cell.fail { color: #a30000; }

            /* ── Specific cell types ─────────────────────────────────────── */
            .key-cell   { font-family: monospace; font-size: 8pt; word-break: break-all; color: #222; }
            .hash-cell  { font-family: monospace; font-size: 8pt; word-break: break-all; }
            .src-cell   { font-size: 7.5pt; color: #444; }
            .num-cell   { text-align: right; width: 30px; color: #666; }
            .na         { color: #999; font-style: italic; }

            /* ── Pre blocks ──────────────────────────────────────────────── */
            pre {
                font-family: monospace;
                font-size: 7.5pt;
                white-space: pre-wrap;
                word-break: break-all;
                background: #f7f7f7;
                border: 1px solid #ddd;
                padding: 8px;
                margin: 0;
            }

            /* ── Notes ───────────────────────────────────────────────────── */
            .note {
                font-size: 7.5pt;
                color: #777;
                margin-bottom: 6px;
                font-style: italic;
            }
        """;
    }
}
