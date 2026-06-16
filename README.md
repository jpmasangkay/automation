# 🔍 Site Content Comparator
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Playwright](https://img.shields.io/badge/Playwright-1.49.0-2EAD33?style=for-the-badge&logo=playwright&logoColor=white)](https://playwright.dev/java/)
[![Maven](https://img.shields.io/badge/Maven-Build-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](https://maven.apache.org/)

### Automated Visual & Content Regression Testing

**Extract. Compare. Report. Automatically.**

A robust Java-based automation tool designed to perform deep, structural, and visual comparisons between two web pages. Powered by Microsoft Playwright, it extracts and diffs raw text, images, links, metadata, and data layers, generating a comprehensive PDF report of the findings.

---

## ✨ Features

### 📄 Text & DOM Comparison
- **Line-level text diffing** — extracts pure text without scripts/styles and identifies unique or missing text nodes
- **Smart normalization** — removes extra whitespace and standardizes casing for accurate comparisons

### 🖼️ Visual & Image Analysis
- **Pixel-level image matching** — takes direct screenshots of every image element (`<img>`) and compares their MD5 hashes
- Automatically scrolls elements into view to ensure lazy-loaded images are captured

### 🔗 Link Validation
- **Deep link extraction** — gathers URLs from `href`, `data-href`, `data-url`, and inline `onclick` events
- **Slug-based comparison** — intelligently compares link destinations ignoring query parameters or domains

### 📊 SEO & Analytics Validation
- **Metadata comparison** — extracts and diffs `<title>` and `<meta>` tags (OpenGraph, charset, etc.)
- **Timestamp filtering** — automatically ignores volatile metadata (e.g., `last-modified`, `article:published_time`)
- **DataLayer extraction** — captures and diffs `window.dataLayer` JSON for Google Tag Manager (GTM) and analytics validation

### 🚀 Performance & Reporting
- **Concurrent execution** — uses an `ExecutorService` thread pool to extract data from both sites simultaneously for faster execution
- **Automated PDF Reports** — generates a detailed, styled HTML summary and uses Playwright's headless browser to print a pixel-perfect A4 PDF report

---

## 🛠️ Tech Stack

| Technology | Purpose |
|-----------|---------|
| **Java 21** | Core language and logic implementation |
| **Playwright Java** | Headless browser automation (Chromium) for accurate DOM rendering |
| **Maven** | Dependency management and build tool |
| **Java Concurrency** | `ExecutorService` and `Future` for parallel site scraping |

---

## 🚀 Getting Started

### Prerequisites

- [Java Development Kit (JDK) 21+](https://adoptium.net/)
- [Apache Maven](https://maven.apache.org/)

### Installation

```bash
# Clone the repository
git clone <your-repository-url>
cd automation

# Install dependencies and build the project
mvn clean install
```

### Execution

The application currently has the target URLs configured in the `Main.java` file. To run the comparison:

```bash
# Compile and execute the Main class
mvn exec:java -Dexec.mainClass="com.automation.Main"
```

*Note: You can modify `SITE_A` and `SITE_B` constants in `Main.java` to test different URLs.*

---

## 📁 Project Structure

```text
automation/
├── pom.xml                          # Maven dependencies and build config
└── src/main/java/com/automation/
    ├── Main.java                    # Entry point — Thread pool and orchestrator
    ├── model/                       # Data structures
    │   ├── ComparisonResult.java    # Aggregated diff results
    │   ├── SiteData.java            # Extracted page data payload
    │   ├── ImageData.java           # Image src, byte array, and MD5 hash
    │   └── LinkData.java            # Extracted link and computed slug
    ├── extractor/
    │   └── ContentExtractor.java    # Playwright logic to navigate and scrape
    ├── comparator/
    │   └── SiteComparator.java      # Diffing logic for text, images, and metadata
    └── report/
        └── ReportFormatter.java     # HTML generation and PDF export via Playwright
```

---

## 🧠 How It Works

The Site Content Comparator operates in a structured pipeline:

1. **Parallel Extraction** — The orchestrator (`Main.java`) spawns two parallel threads using an `ExecutorService`. Each thread launches a headless Chromium browser via Playwright, navigates to the target URL, and waits for the load state.
2. **Deep Scraping** — The `ContentExtractor` evaluates JavaScript on the page to strip out unwanted tags (scripts, styles) and extract raw `innerText`. It takes targeted screenshots of all images and computes MD5 hashes. It also harvests metadata and the `window.dataLayer`.
3. **Data Diffing** — Once both sites are extracted, the `SiteComparator` performs a rigorous comparison. It uses Set operations to find missing text lines, matches image hashes, diffs link slugs, and highlights mismatched metadata keys.
4. **Report Generation** — The `ReportFormatter` takes the `ComparisonResult` and builds a responsive HTML document with an embedded stylesheet. Playwright then loads this HTML into a blank page and prints it as a formatted PDF.

---
