package dk.northtech.dasscofileproxy.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("deploy-info")
public record DeployInfoConfig(String artifact, String version, String podName, String buildTime) {
}
