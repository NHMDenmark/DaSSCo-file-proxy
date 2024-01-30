package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import dk.northtech.dasscofileproxy.domain.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Arrays;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@DirtiesContext
class HttpShareServiceTest {
    @Inject
    HttpShareService httpShareService;
    @Inject
    DockerConfig dockerConfig;

    @Container
    static GenericContainer postgreSQL = new GenericContainer(DockerImageName.parse("apache/age:v1.1.0"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "dassco_file_proxy")
            .withEnv("POSTGRES_USER", "dassco_file_proxy")
            .withEnv("POSTGRES_PASSWORD", "dassco_file_proxy");

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.jdbcUrl", () -> "jdbc:postgresql://localhost:" + postgreSQL.getFirstMappedPort() + "/dassco_file_proxy");
    }

    @Test
    void createDirectory() {
        SharedAsset azzet1 = new SharedAsset(null, null, "azzet_1", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/azzet_1/", AccessType.WRITE, Instant.now(), 10, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        assertThat(directory1.directoryId()).isNotNull();
        StorageMetrics storageMetrics = httpShareService.getStorageMetrics();
        assertThat(storageMetrics.all_allocated_storage_mb()).isEqualTo(10);
        assertThat(storageMetrics.cache_storage_mb()).isEqualTo(200);
        assertThat(storageMetrics.remaining_storage_mb()).isEqualTo(1790);
    }

    @Test
    void deleteDirectory() {
        SharedAsset azzet1 = new SharedAsset(null, null, "azzet_1", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/azzet_1/", AccessType.WRITE, Instant.now(), 10, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        StorageMetrics storageMetricsBefore = httpShareService.getStorageMetrics();
        httpShareService.deleteDirectory(directory1.directoryId());
        StorageMetrics storageMetricsAfter = httpShareService.getStorageMetrics();
        assertThat(storageMetricsAfter.remaining_storage_mb()).isEqualTo(storageMetricsBefore.remaining_storage_mb() + 10);
        assertThat(storageMetricsAfter.all_allocated_storage_mb()).isEqualTo(storageMetricsBefore.all_allocated_storage_mb() -10);
    }
}