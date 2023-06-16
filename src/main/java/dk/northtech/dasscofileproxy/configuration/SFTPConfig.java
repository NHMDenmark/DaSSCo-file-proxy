package dk.northtech.dasscofileproxy.configuration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sftp.config")
public record SFTPConfig(String host, Integer port, String username, String privateKey, String passphrase
        , String localFolder, String remoteFolder) {
}
