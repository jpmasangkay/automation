package com.automation.model;

public class ImageData {
    private final int index;
    private final String src;
    private final String hash;
    private final byte[] bytes;

    public ImageData(int index, String src, byte[] bytes, String hash) {
        this.index = index;
        this.src = src;
        this.bytes = bytes;
        this.hash = hash;
    }

    public int getIndex() { return index; }
    public String getSrc() { return src; }
    public String getHash() { return hash; }
    public byte[] getBytes() { return bytes; }
}
