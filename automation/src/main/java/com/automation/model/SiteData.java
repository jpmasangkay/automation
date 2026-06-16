package com.automation.model;

import java.util.List;
import java.util.Map;

public class SiteData {
    private final String label;
    private final String url;
    private final String rawText;
    private final String normalizedText;
    private final List<ImageData> images;
    private final List<LinkData> links;
    private final Map<String, String> metadata;
    private final String dataLayerJson;
    private final long extractionTimeMillis;

    public SiteData(String label, String url, String rawText, String normalizedText,
                    List<ImageData> images, List<LinkData> links,
                    Map<String, String> metadata, String dataLayerJson,
                    long extractionTimeMillis) {
        this.label = label;
        this.url = url;
        this.rawText = rawText;
        this.normalizedText = normalizedText;
        this.images = images;
        this.links = links;
        this.metadata = metadata;
        this.dataLayerJson = dataLayerJson;
        this.extractionTimeMillis = extractionTimeMillis;
    }

    public String getLabel()                  { return label; }
    public String getUrl()                    { return url; }
    public String getRawText()                { return rawText; }
    public String getText()                   { return normalizedText; }
    public List<ImageData> getImages()        { return images; }
    public List<LinkData> getLinks()          { return links; }
    public Map<String, String> getMetadata()  { return metadata; }
    public String getDataLayerJson()          { return dataLayerJson; }
    public long getExtractionTimeMillis()     { return extractionTimeMillis; }
}
