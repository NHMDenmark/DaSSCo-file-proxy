package dk.northtech.dasscofileproxy.configuration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("storage")
public record StorageConfig(String host, Integer port, String username, String privateKey, String passphrase
        , String localFolder, String remoteFolder, int erdaConnectionPoolSize, String http) {
}
