package dk.northtech.dasscofileproxy.service;

import java.util.List;

@FunctionalInterface
public interface AssetBundleSizeCalculator {
    long totalSizeBytes(List<String> assetGuids);
}
