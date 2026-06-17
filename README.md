# 🔍 Site Content Comparator
[![Java](https://img.shields.io/badge/Java-21+-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Playwright](https://img.shields.io/badge/Playwright-1.49.0-2EAD33?style=for-the-badge&logo=playwright&logoColor=white)](https://playwright.dev/java/)
[![Maven](https://img.shields.io/badge/Maven-Build-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](https://maven.apache.org/)

### Automated Visual & Content Regression Testing

**Extract. Compare. Report. Automatically.**

A robust Java-based automation tool designed to perform deep, structural, and visual comparisons between two web pages. Powered by Microsoft Playwright, it extracts and diffs raw text, images, links, metadata, and data layers, generating a comprehensive PDF report of the findings.

---

## 🚀 Quick Start (Non-Programmers)

> **No coding required.** Just follow these 3 steps.

### Step 1 — Prepare your Excel file

Create an `.xlsx` file with your URLs:

| Column A (Site A)                   | Column B (Site B)                        |
|-------------------------------------|------------------------------------------|
| https://staging.example.com/page    | https://www.example.com/page             |
| https://staging.example.com/about   | https://www.example.com/about            |

- A header row like `"Site A"` / `"Site B"` is fine — it will be skipped automatically.
- Both URLs must start with `http://` or `https://`.

### Step 2 — Drop it in the `data\` folder

Place your `.xlsx` file inside the **`data\`** folder (located in the same folder as `run.bat`).

```
Automation\
├── data\
│   └── your_comparison_file.xlsx   ← put it here
├── run.bat
└── ...
```

> **Tip:** You can also drag and drop your `.xlsx` file directly onto `run.bat`.

### Step 3 — Double-click `run.bat`

That's it! The script will:
1. ✅ Auto-detect Java and Maven on your machine
2. 🔨 Build the tool on the first run (takes ~1-2 min, once only)
3. 🌐 Install the browser engine on the first run (once only)
4. 📄 Automatically find your Excel file in `data\`
5. 📊 Generate PDF reports in the `reports\` folder
6. 📂 Open the `reports\` folder when done

---

## 📋 Prerequisites

The script will check for Java automatically and give a download link if missing. It uses a bundled Maven Wrapper to download build tools if needed.

| Requirement | Minimum Version | Download |
|-------------|----------------|---------|
| Java (JDK)  | 21+            | [adoptium.net](https://adoptium.net/) |

> **Already using IntelliJ IDEA?** Java is likely already on your machine — the script will find it automatically.

---

## 🗂️ Folder Structure

```text
Automation\
├── data\                            ← DROP YOUR .xlsx FILES HERE
│   └── README.txt
├── reports\                         ← PDF reports appear here automatically
├── run.bat                          ← Double-click to run
├── rebuild.bat                      ← Force a fresh rebuild (after code changes)
├── pom.xml                          ← Maven build config
├── test_comparisons.xlsx            ← Sample/fallback comparison file
└── src\main\java\com\automation\
    ├── Main.java                    ← Entry point — orchestrator
    ├── model\                       ← Data structures
    │   ├── ComparisonResult.java
    │   ├── SiteData.java
    │   ├── ImageData.java
    │   └── LinkData.java
    ├── extractor\
    │   └── ContentExtractor.java    ← Playwright browser scraping
    ├── comparator\
    │   └── SiteComparator.java      ← Diff logic
    └── report\
        └── ReportFormatter.java     ← HTML + PDF report generation
```

---

## ✨ Features

### 📄 Text & DOM Comparison
- **Line-level text diffing** — extracts pure text without scripts/styles and identifies unique or missing text nodes
- **Smart normalization** — removes extra whitespace and standardizes casing for accurate comparisons

### 🖼️ Visual & Image Analysis
- **Pixel-level image matching** — takes direct screenshots of every `<img>` element and compares their MD5 hashes
- **Perceptual hashing** — detects visually identical images even when binary content differs (e.g. CDN re-encoding)
- Automatically scrolls elements into view to capture lazy-loaded images

### 🔗 Link Validation
- **Deep link extraction** — gathers URLs from `href`, `data-href`, `data-url`, and inline `onclick` events
- **Slug-based comparison** — intelligently compares link destinations ignoring query parameters or domains

### 📊 SEO & Analytics Validation
- **Metadata comparison** — extracts and diffs `<title>` and `<meta>` tags (OpenGraph, charset, etc.)
- **Timestamp filtering** — automatically ignores volatile metadata (e.g., `last-modified`, `article:published_time`)
- **DataLayer extraction** — captures and diffs `window.dataLayer` JSON for Google Tag Manager (GTM) validation

### 🚀 Performance & Reporting
- **Concurrent execution** — uses an `ExecutorService` thread pool to extract both sites simultaneously
- **Automated PDF Reports** — generates a detailed, styled HTML summary and uses Playwright's headless browser to print a pixel-perfect A4 PDF report
- **Pass/Fail summary** — clear overall verdict with per-pair counters at the end

---

## 🔧 For Developers

### Build manually
```bash
mvnw.cmd clean package --no-transfer-progress
# Output: target/automation-runner.jar
```

### Run manually
```bash
java -jar target/automation-runner.jar data/your_file.xlsx
```

### Excel file auto-detection priority
1. Path passed as a command-line argument
23. **One** `.xlsx` in `data\` → auto-selects it
4. **Multiple** `.xlsx` in `data\` → numbered menu to pick
5. Fallback → `test_comparisons.xlsx` in root

---

## 🧠 How It Works

The Site Content Comparator operates in a structured pipeline:

1. **Parallel Extraction** — Two parallel threads launch headless Chromium browsers via Playwright, navigate to the target URLs, and wait for full load state.
2. **Deep Scraping** — The `ContentExtractor` evaluates JavaScript on the page to strip scripts/styles and extract raw `innerText`. It screenshots all images and computes MD5 + perceptual hashes. It harvests metadata and `window.dataLayer`.
3. **Data Diffing** — The `SiteComparator` performs a rigorous comparison using Set operations to find missing text lines, matches image hashes, diffs link slugs, and highlights mismatched metadata keys.
4. **Report Generation** — The `ReportFormatter` builds a responsive HTML document and Playwright prints it as a formatted A4 PDF.
