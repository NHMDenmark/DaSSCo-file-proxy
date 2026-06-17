package dk.northtech.dasscofileproxy.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asset-bundles")
public record AssetBundleConfig(long maxSizeGb) {
    private static final long BYTES_PER_GB = 1_000_000_000L;

    public long maxSizeBytes() {
        return Math.multiplyExact(maxSizeGb, BYTES_PER_GB);
    }
}
