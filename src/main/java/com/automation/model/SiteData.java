package com.automation.model;

import java.util.List;
import java.util.Map;

public class SiteData {
    private final String label;
    private final String url;
    private final String rawText;
    private final List<ImageData> images;
    private final List<LinkData> links;
    private final Map<String, String> metadata;
    private final Map<String, String> dataLayer;
    private final Map<String, String> functionalityComponents;
    private final long extractionTimeMillis;

    public SiteData(String label, String url, String rawText,
                    List<ImageData> images, List<LinkData> links,
                    Map<String, String> metadata, Map<String, String> dataLayer,
                    Map<String, String> functionalityComponents,
                    long extractionTimeMillis) {
        this.label = label;
        this.url = url;
        this.rawText = rawText;
        this.images = images;
        this.links = links;
        this.metadata = metadata;
        this.dataLayer = dataLayer;
        this.functionalityComponents = functionalityComponents;
        this.extractionTimeMillis = extractionTimeMillis;
    }

    public String getLabel()                  { return label; }
    public String getUrl()                    { return url; }
    public String getRawText()                { return rawText; }
    public List<ImageData> getImages()        { return images; }
    public List<LinkData> getLinks()          { return links; }
    public Map<String, String> getMetadata()  { return metadata; }
    public Map<String, String> getDataLayer() { return dataLayer; }
    public Map<String, String> getFunctionalityComponents() { return functionalityComponents; }
    public long getExtractionTimeMillis()     { return extractionTimeMillis; }
}
