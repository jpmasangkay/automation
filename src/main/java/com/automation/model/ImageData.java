package com.automation.model;

public class ImageData {
    private final int index;
    private final String src;
    private final String hash;           // MD5 of raw downloaded bytes (exact binary match)
    private final String perceptualHash; // MD5 of fixed-size visual render (same-image-different-path match)
    private final byte[] bytes;

    public ImageData(int index, String src, byte[] bytes, String hash, String perceptualHash) {
        this.index = index;
        this.src = src;
        this.bytes = bytes;
        this.hash = hash;
        this.perceptualHash = perceptualHash;
    }

    public int getIndex() { return index; }
    public String getSrc() { return src; }
    public String getHash() { return hash; }
    public String getPerceptualHash() { return perceptualHash; }
    public byte[] getBytes() { return bytes; }
}
