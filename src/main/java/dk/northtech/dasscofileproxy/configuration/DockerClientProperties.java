package dk.northtech.dasscofileproxy.configuration;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import jakarta.inject.Inject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class DockerClientProperties {
    private final DockerConfig dockerConfig;

    @Inject
    public DockerClientProperties(DockerConfig dockerConfig) {
        this.dockerConfig = dockerConfig;
    }

    @Bean
    public DefaultDockerClientConfig dockerConfig() {
        System.out.println(dockerConfig.dockerHost());
        return DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerConfig.dockerHost()).build();
    }

    @Bean
    public DockerClient dockerHttpClient(DefaultDockerClientConfig dockerConfig) {
        DockerHttpClient dockerHttpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

        return DockerClientImpl.getInstance(dockerConfig, dockerHttpClient);
    }
}
