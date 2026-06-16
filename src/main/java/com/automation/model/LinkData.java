package com.automation.model;

import java.util.Objects;

public class LinkData {
    private final String originalHref;
    private final String slug;

    public LinkData(String originalHref, String slug) {
        this.originalHref = originalHref;
        this.slug = slug;
    }

    public String getOriginalHref() { return originalHref; }
    public String getSlug() { return slug; }

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
        return slug + " (from: " + originalHref + ")";
    }
}
