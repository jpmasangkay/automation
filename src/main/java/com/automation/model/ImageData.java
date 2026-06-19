package com.automation.model;

public class ImageData {
    private int index;
    private String src;
    private String hash;           // MD5 of raw downloaded bytes (exact binary match)
    private String perceptualHash; // MD5 of fixed-size visual render (same-image-different-path match)

    public ImageData(int index, String src, String hash, String perceptualHash) {
        this.index = index;
        this.src = src;
        this.hash = hash;
        this.perceptualHash = perceptualHash;
    }

    public int getIndex() { return index; }
    public String getSrc() { return src; }
    public String getHash() { return hash; }
    public String getPerceptualHash() { return perceptualHash; }
}
