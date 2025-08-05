package dk.northtech.dasscofileproxy.configuration;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("share")
public record ShareConfig(String mountFolder, String parkingFolder, String nodeHost, int cacheDiskspace, int maxErdaSyncAttempts, int totalDiskSpace, String cacheFolder, String thumbnailMimeTypes) {

}
