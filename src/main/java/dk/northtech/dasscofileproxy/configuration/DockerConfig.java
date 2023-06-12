package dk.northtech.dasscofileproxy.configuration;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("docker")
public record DockerConfig (String dockerHost, String mountFolder, Integer portRangeStart, Integer portRangeEnd) {
}
