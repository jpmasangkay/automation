package com.automation.model;

import java.util.Objects;

public class LinkData {
    private String originalHref;
    private String slug;
    private String linkText;

    public LinkData(String originalHref, String slug, String linkText) {
        this.originalHref = originalHref;
        this.slug = slug;
        this.linkText = linkText;
    }

    public String getOriginalHref() { return originalHref; }
    public String getSlug() { return slug; }
    public String getLinkText() { return linkText; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LinkData linkData = (LinkData) o;
        return Objects.equals(slug, linkData.slug); // We compare based on SLUG
    }

    @Override
    public int hashCode() {
        return Objects.hash(slug); // Hash based on SLUG
    }

    @Override
    public String toString() {
        return slug + " (text: " + linkText + ", from: " + originalHref + ")";
    }
}
