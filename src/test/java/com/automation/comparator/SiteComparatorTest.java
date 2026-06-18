package com.automation.comparator;

import com.automation.model.ComparisonResult;
import com.automation.model.ComparisonResult.ImageMatch;
import com.automation.model.ImageData;
import com.automation.model.SiteData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SiteComparatorTest {

    @Test
    public void testImageMatchingBySlug() {
        ImageData imgA1 = new ImageData(0, "https://a.com/images/hero.jpg", "hash1", "ph1");
        ImageData imgA2 = new ImageData(1, "https://a.com/images/banner.jpg", "hash2", "ph2");
        
        // Site B has them in reverse order, but same slugs
        ImageData imgB1 = new ImageData(0, "https://b.com/assets/banner.jpg", "hash2", "ph2");
        ImageData imgB2 = new ImageData(1, "https://b.com/assets/hero.jpg", "hash1", "ph1");

        SiteData siteA = new SiteData("A", "http://a.com", "", List.of(imgA1, imgA2), List.of(), Map.of(), Map.of(), 0, new byte[0]);
        SiteData siteB = new SiteData("B", "http://b.com", "", List.of(imgB1, imgB2), List.of(), Map.of(), Map.of(), 0, new byte[0]);

        SiteComparator comparator = new SiteComparator();
        ComparisonResult result = comparator.compare(siteA, siteB);

        assertTrue(result.imageDiff().matches());
        assertEquals(2, result.imageDiff().matchedImagesCount());
        
        List<ImageMatch> matches = result.imageDiff().matchesList();
        assertEquals(2, matches.size());
        
        // Hero matched with Hero
        assertEquals(imgA1, matches.get(0).imgA());
        assertEquals(imgB2, matches.get(0).imgB());
        
        // Banner matched with Banner
        assertEquals(imgA2, matches.get(1).imgA());
        assertEquals(imgB1, matches.get(1).imgB());
    }

    @Test
    public void testImagePositionalFallback() {
        ImageData imgA = new ImageData(0, "data:image/png;base64,123", "hash1", "ph1");
        ImageData imgB = new ImageData(0, "data:image/png;base64,123", "hash1", "ph1");

        SiteData siteA = new SiteData("A", "http://a.com", "", List.of(imgA), List.of(), Map.of(), Map.of(), 0, new byte[0]);
        SiteData siteB = new SiteData("B", "http://b.com", "", List.of(imgB), List.of(), Map.of(), Map.of(), 0, new byte[0]);

        SiteComparator comparator = new SiteComparator();
        ComparisonResult result = comparator.compare(siteA, siteB);

        assertTrue(result.imageDiff().matches());
        assertEquals(1, result.imageDiff().matchedImagesCount());
        assertEquals(imgA, result.imageDiff().matchesList().get(0).imgA());
        assertEquals(imgB, result.imageDiff().matchesList().get(0).imgB());
    }
}
