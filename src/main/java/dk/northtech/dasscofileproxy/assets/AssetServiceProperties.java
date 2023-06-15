package dk.northtech.dasscofileproxy.assets;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asset-service")
public record AssetServiceProperties(String rootUrl) {
}
