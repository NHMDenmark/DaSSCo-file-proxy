package dk.northtech.dasscofileproxy.configuration;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("share")
public record ShareConfig(String mountFolder, String nodeHost, int totalDiskpace, int cacheDiskspace) {
}
