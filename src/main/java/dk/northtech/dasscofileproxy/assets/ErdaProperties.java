package dk.northtech.dasscofileproxy.assets;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("erda")
public record ErdaProperties(String server, int port, String user, String password) {
}
