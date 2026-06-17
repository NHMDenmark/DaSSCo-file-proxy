package dk.northtech.dasscofileproxy.service;

public class AssetBundleTooLargeException extends RuntimeException {
    private final long totalSizeBytes;
    private final long maxSizeBytes;
    private final int assetCount;

    public AssetBundleTooLargeException(long totalSizeBytes, long maxSizeBytes, int assetCount) {
        super("Selected bundle is too large. Select fewer assets.");
        this.totalSizeBytes = totalSizeBytes;
        this.maxSizeBytes = maxSizeBytes;
        this.assetCount = assetCount;
    }

    public long totalSizeBytes() {
        return totalSizeBytes;
    }

    public long maxSizeBytes() {
        return maxSizeBytes;
    }

    public int assetCount() {
        return assetCount;
    }
}
