package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.webapi.model.AssetStorageAllocation;
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

@SpringBootTest
@Testcontainers
@DirtiesContext
class HttpShareServiceTest {
    @Inject
    HttpShareService httpShareService;
    @Inject
    FileService fileservice;
    @Inject
    ShareConfig shareConfig;
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
        StorageMetrics storageMetrics = httpShareService.getStorageMetrics();
        SharedAsset azzet1 = new SharedAsset(null, null, "createDirectory", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/createDirectory/", "test.dassco.dk", AccessType.WRITE, Instant.now(), 10,false,0, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        assertThat(directory1.directoryId()).isNotNull();
        StorageMetrics result = httpShareService.getStorageMetrics();
        assertThat(result.all_allocated_storage_mb()).isEqualTo(storageMetrics.all_allocated_storage_mb() + 10);
        assertThat(result.cache_storage_mb()).isEqualTo(200);
        assertThat(result.remaining_storage_mb()).isEqualTo(storageMetrics.remaining_storage_mb()-10);
    }

    @Test
    void alloc8Extra() {
        SharedAsset azzet1 = new SharedAsset(null, null, "alloc8Extra", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        StorageMetrics storageMetrics = httpShareService.getStorageMetrics();
        Directory directory = new Directory(null, "/i1/c1/alloc8Extra/", "test.dassco.dk", AccessType.WRITE, Instant.now(), 10,false,0, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        HttpInfo httpInfo = httpShareService.allocateStorage(new AssetStorageAllocation("alloc8Extra", 14));
        assertThat(httpInfo.http_allocation_status()).isEqualTo(HttpAllocationStatus.SUCCESS);
        StorageMetrics result = httpShareService.getStorageMetrics();
        assertThat(result.all_allocated_storage_mb()).isEqualTo(storageMetrics.all_allocated_storage_mb() + 14);
        assertThat(result.cache_storage_mb()).isEqualTo(200);
        assertThat(result.remaining_storage_mb()).isEqualTo(storageMetrics.remaining_storage_mb()-14);
    }

    @Test
    void alloc8ExtraNotEnoughSpace() {
        SharedAsset azzet1 = new SharedAsset(null, null, "alloc8ExtraNotEnoughSpace", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/alloc8ExtraNotEnoughSpace/", "test.dassco.dk", AccessType.WRITE, Instant.now(), 10,false,0, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        StorageMetrics storageMetrics = httpShareService.getStorageMetrics();
        HttpInfo httpInfo = httpShareService.allocateStorage(new AssetStorageAllocation("alloc8ExtraNotEnoughSpace", shareConfig.totalDiskSpace() -9));
        StorageMetrics resultMetrics = httpShareService.getStorageMetrics();
        assertThat(httpInfo.http_allocation_status()).isEqualTo(HttpAllocationStatus.DISK_FULL);
        assertThat(resultMetrics.all_allocated_storage_mb()).isEqualTo(storageMetrics.all_allocated_storage_mb());
        assertThat(resultMetrics.cache_storage_mb()).isEqualTo(resultMetrics.cache_storage_mb());
        assertThat(resultMetrics.remaining_storage_mb()).isEqualTo(resultMetrics.remaining_storage_mb());
    }

    @Test
    void deleteDirectory() {
        SharedAsset azzet1 = new SharedAsset(null, null, "deleteDirectory", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/deleteDirectory/", "test.dassco.dk", AccessType.WRITE, Instant.now(), 10,false, 0,Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        StorageMetrics storageMetricsBefore = httpShareService.getStorageMetrics();
        fileservice.deleteDirectory(directory1.directoryId());
        StorageMetrics storageMetricsAfter = httpShareService.getStorageMetrics();
        assertThat(storageMetricsAfter.remaining_storage_mb()).isEqualTo(storageMetricsBefore.remaining_storage_mb() + 10);
        assertThat(storageMetricsAfter.all_allocated_storage_mb()).isEqualTo(storageMetricsBefore.all_allocated_storage_mb() -10);
    }
}