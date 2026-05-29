package dk.northtech.dasscofileproxy.service;

public record AssetBundleJobSnapshot(
        String jobId,
        AssetBundleJobStatus status,
        int totalAssets,
        int processedAssets,
        String message
) {
}
