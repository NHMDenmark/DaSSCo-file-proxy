package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.repository.FileRepository;
import dk.northtech.dasscofileproxy.webapi.model.AssetStorageAllocation;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.checkerframework.checker.units.qual.C;
import org.jdbi.v3.core.Jdbi;
import org.junit.Ignore;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.validation.constraints.Min;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

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

    @Inject
    Jdbi jdbi;

    private static final Logger logger = LoggerFactory.getLogger(HttpShareServiceTest.class);
    private static Network network = Network.newNetwork();
    @Container
    static GenericContainer postgreSQL = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName( "dassco_file_proxy")
            .withPassword("dassco_file_proxy")
            .withUsername("dassco_file_proxy")
            .withExposedPorts(5432)
//            .waitingFor(Wait.forLogMessage("ready to accept connections",1))
            .withNetwork(network).withNetworkAliases("database");
    //            .withEnv("POSTGRES_DB", "dassco_file_proxy")
//            .withEnv("POSTGRES_USER", "dassco_file_proxy")
//            .withEnv("POSTGRES_PASSWORD", "dassco_file_proxy")
    @Container
    static GenericContainer arsBackend;

    static {
        postgreSQL.start();
        Slf4jLogConsumer logConsumer = new Slf4jLogConsumer(logger);
        arsBackend = new GenericContainer(DockerImageName.parse("nhmdenmark/dassco-asset-service:1.3.4"))
                .withEnv("POSTGRES_URL", "jdbc:postgresql://database:"+5432+"/dassco_file_proxy")
                .withEnv("LIQUIBASE_CONTEXTS",  "default, development, test")
                .dependsOn(postgreSQL)
                .withLogConsumer(logConsumer)
                .waitingFor(Wait.forLogMessage(".*Started DasscoAssetServiceApplication.*\\n", 1)).withStartupTimeout(Duration.ofSeconds(180))
                .withNetwork(network);

    }
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
        long usableSpace = new File(shareConfig.mountFolder()).getUsableSpace();

        HttpInfo httpInfo = httpShareService.allocateStorage(new AssetStorageAllocation("alloc8ExtraNotEnoughSpace", (int) ((usableSpace / 1000000) +1000)));
        StorageMetrics resultMetrics = httpShareService.getStorageMetrics();
        System.out.println(storageMetrics);
        System.out.println(resultMetrics);
        assertThat(httpInfo.http_allocation_status()).isEqualTo(HttpAllocationStatus.DISK_FULL);
        assertThat(resultMetrics.all_allocated_storage_mb()).isEqualTo(storageMetrics.all_allocated_storage_mb());
        assertThat(resultMetrics.cache_storage_mb()).isEqualTo(storageMetrics.cache_storage_mb());
        assertThat(resultMetrics.remaining_storage_mb()).isEqualTo(storageMetrics.remaining_storage_mb());
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

    @Test
    void deleteShare() {
        StorageMetrics storageMetrics = httpShareService.getStorageMetrics();
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        SharedAsset azzet1 = new SharedAsset(null,null, "deleteShare_1", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/createDirectory/", "test.dassco.dk", AccessType.WRITE, Instant.now(), 10,false,0, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        User user = new User();
        user.username = "Bazviola";
        //simulate adding file to newly created asset...
        jdbi.withHandle(h -> {
            FileRepository attach = h.attach(FileRepository.class);
            attach.insertFile(new DasscoFile(null, "deleteShare_1", "/teztific8", 100000L, 1234, false, FileSyncStatus.NEW_FILE));
            return h;
        });

        // ...syncing asset...
        fileservice.markFilesAsSynced("deleteShare_1");
        jdbi.withHandle(h -> {
            FileRepository attach = h.attach(FileRepository.class);
            // ...checking out asset and adding additional files to it.
            attach.insertFile(new DasscoFile(null, "deleteShare_1", "/test/asdf.pdf", 100000L, 1234, false, FileSyncStatus.NEW_FILE));
            return h;
        });

        assertThat(directory1.directoryId()).isNotNull();
        HttpInfo httpInfo = httpShareService.deleteShare(user, "deleteShare_1");
        assertThat(httpInfo.http_allocation_status()).isEqualTo(HttpAllocationStatus.SUCCESS);
        List<DasscoFile> deleteShare1 = fileservice.listFilesByAssetGuid("deleteShare_1");
        assertThat(deleteShare1.size()).isEqualTo(1);
    }
    public void createAsset(String assetId) {

    }
    @Test
    @Disabled("Requires keycloak so is disabled for now")
    void testCreateHttpShareInternal(){
        MinimalAsset minimalAsset = new MinimalAsset("testCreateHttpShareInternal", null, null, null);
        List<MinimalAsset> listMinimalAsset = new ArrayList<>();
        listMinimalAsset.add(minimalAsset);
        List<String> listUsers = new ArrayList<>();
        listUsers.add("test-user");
        User user = new User();
        user.username = "test-user";
        CreationObj creationObj = new CreationObj(listMinimalAsset, listUsers, 1);
        HttpInfo httpInfo = httpShareService.createHttpShareInternal(creationObj, user);
        assertThat(httpInfo.http_allocation_status().toString()).isEqualTo("SUCCESS");
        // Remove folder?
    }

    @Test
    void testCreateHttpShareInternalNoUser(){
        User user = new User();
        List<MinimalAsset> listMinimalAsset = new ArrayList<>();
        List<String> listUsers = new ArrayList<>();
        CreationObj creationObj = new CreationObj(listMinimalAsset, listUsers, 1);
        IllegalArgumentException badRequestException = assertThrows(IllegalArgumentException.class, () -> httpShareService.createHttpShareInternal(creationObj, user));
        assertThat(badRequestException).hasMessageThat().isEqualTo("You have to provide users and an asset in this call");
    }

    @Test
    void testCreateHttpShareInternalMoreThanOneAsset(){
        User user = new User();
        user.username = "test-user";
        List<MinimalAsset> minimalAssetList = new ArrayList<>();
        List<String> userList = new ArrayList<>();
        MinimalAsset minimalAsset1 = new MinimalAsset("test-asset-1", null, null, null);
        MinimalAsset minimalAsset2 = new MinimalAsset("test-asset-2", null, null, null);
        minimalAssetList.add(minimalAsset1);
        minimalAssetList.add(minimalAsset2);
        userList.add("test-user");
        CreationObj creationObj = new CreationObj(minimalAssetList, userList, 1);
        IllegalArgumentException illegalArgumentException = assertThrows(IllegalArgumentException.class, () -> httpShareService.createHttpShareInternal(creationObj, user));
        assertThat(illegalArgumentException).hasMessageThat().isEqualTo("Number of assets must be one");
    }

    @Test
    void testMapCreationObject() {
        AssetFull assetFull = new AssetFull();
        MinimalAsset minazzet = new MinimalAsset("1234", Set.of("123"), "inztitution", "kollection");
        assetFull.asset_guid = "1234";
        assetFull.collection = "kollection";
        assetFull.institution = "inztitution";
        CreationObj result = HttpShareService.mapCreationObject(new CreationObj(List.of(minazzet), new ArrayList<>(), 123), minazzet, assetFull);
        MinimalAsset minimalAsset = result.assets().getFirst();
        assertThat(minimalAsset.asset_guid()).isEqualTo("1234");
        assertThat(minimalAsset.parent_guids()).contains("123");
        assertThat(minimalAsset.collection()).isEqualTo("kollection");
        assertThat(minimalAsset.institution()).isEqualTo("inztitution");
    }

    @Test
    void testMapCreationObjectNull() {
        AssetFull assetFull = new AssetFull();
        MinimalAsset minazzet = new MinimalAsset("1234", Set.of("123"), null, null);
        assetFull.asset_guid = "1234";
        assetFull.collection = "kollection";
        assetFull.institution = "inztitution";
        CreationObj result = HttpShareService.mapCreationObject(new CreationObj(List.of(minazzet), new ArrayList<>(), 123), minazzet, assetFull);
        MinimalAsset minimalAsset = result.assets().getFirst();
        assertThat(minimalAsset.asset_guid()).isEqualTo("1234");
        assertThat(minimalAsset.parent_guids()).contains("123");
        assertThat(minimalAsset.collection()).isEqualTo("kollection");
        assertThat(minimalAsset.institution()).isEqualTo("inztitution");
    }
}
