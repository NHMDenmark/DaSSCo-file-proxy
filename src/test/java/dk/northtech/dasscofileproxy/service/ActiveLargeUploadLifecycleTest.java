package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.domain.AccessType;
import dk.northtech.dasscofileproxy.domain.AssetUpdate;
import dk.northtech.dasscofileproxy.domain.Directory;
import dk.northtech.dasscofileproxy.domain.SharedAsset;
import dk.northtech.dasscofileproxy.domain.UserAccess;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoConflictException;
import dk.northtech.dasscofileproxy.repository.ActiveLargeUploadRepository;
import dk.northtech.dasscofileproxy.repository.DirectoryRepository;
import dk.northtech.dasscofileproxy.repository.SharedAssetRepository;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class ActiveLargeUploadLifecycleTest {
    @Container
    static PostgreSQLContainer<?> postgreSQL = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("dassco_file_proxy")
            .withUsername("dassco_file_proxy")
            .withPassword("dassco_file_proxy");

    @BeforeEach
    void setUp() {
        localJdbi().useHandle(handle -> {
            handle.execute("DROP TABLE IF EXISTS active_large_uploads");
            handle.execute("DROP TABLE IF EXISTS shared_assets");
            handle.execute("DROP TABLE IF EXISTS directories");
            handle.execute("DROP TYPE IF EXISTS access_type");
            handle.execute("CREATE TYPE access_type AS ENUM ('READ', 'WRITE', 'ADMIN')");
            handle.execute("""
                    CREATE TABLE directories(
                        directory_id bigserial PRIMARY KEY,
                        uri text NOT NULL,
                        node_host text NOT NULL,
                        access access_type NOT NULL,
                        allocated_storage_mb integer NOT NULL,
                        creation_datetime timestamp NOT NULL,
                        awaiting_erda_sync boolean NOT NULL DEFAULT false,
                        erda_sync_attempts integer NOT NULL DEFAULT 0,
                        sync_user text,
                        sync_workstation text,
                        sync_pipeline text,
                        specify_sync_log_id bigint
                    )
                    """);
            handle.execute("""
                    CREATE TABLE shared_assets(
                        directory_id bigint NOT NULL REFERENCES directories(directory_id) ON DELETE CASCADE,
                        asset_guid text NOT NULL,
                        creation_datetime timestamp NOT NULL
                    )
                    """);
            handle.execute("""
                    CREATE TABLE active_large_uploads(
                        upload_id text PRIMARY KEY,
                        asset_guid text NOT NULL,
                        directory_id bigint NOT NULL REFERENCES directories(directory_id) ON DELETE CASCADE,
                        path text NOT NULL,
                        created_at timestamp NOT NULL DEFAULT now(),
                        updated_at timestamp NOT NULL DEFAULT now()
                    )
                    """);
        });
    }

    @Test
    void activeUploadBlocksSynchronization() {
        Jdbi jdbi = localJdbi();
        FileService fileService = new FileService(null, jdbi, null, null, null);
        long directoryId = createDirectory(jdbi, "activeUploadBlocksSynchronization");

        fileService.registerActiveLargeUpload("upload-1", "activeUploadBlocksSynchronization", "/file.tif");

        DasscoConflictException exception = assertThrows(DasscoConflictException.class,
                () -> fileService.scheduleDirectoryForSynchronization(directoryId,
                        new AssetUpdate("activeUploadBlocksSynchronization", null, null, "user")));

        assertThat(exception).hasMessageThat().isEqualTo("Asset has active large file uploads");
    }

    @Test
    void synchronizingDirectoryBlocksActiveUploadRegistration() {
        Jdbi jdbi = localJdbi();
        FileService fileService = new FileService(null, jdbi, null, null, null);
        long directoryId = createDirectory(jdbi, "synchronizingDirectoryBlocksActiveUploadRegistration");
        jdbi.useHandle(handle -> handle.attach(DirectoryRepository.class)
                .scheduleDiretoryForSynchronization(directoryId,
                        new AssetUpdate("synchronizingDirectoryBlocksActiveUploadRegistration", null, null, "user"), null));

        DasscoConflictException exception = assertThrows(DasscoConflictException.class,
                () -> fileService.registerActiveLargeUpload("upload-2",
                        "synchronizingDirectoryBlocksActiveUploadRegistration", "/file.tif"));

        assertThat(exception).hasMessageThat().isEqualTo("Share is synchronizing");
    }

    @Test
    void completedUploadNoLongerBlocksSynchronization() {
        Jdbi jdbi = localJdbi();
        FileService fileService = new FileService(null, jdbi, null, null, null);
        long directoryId = createDirectory(jdbi, "completedUploadNoLongerBlocksSynchronization");

        fileService.registerActiveLargeUpload("upload-3", "completedUploadNoLongerBlocksSynchronization", "/file.tif");
        fileService.unregisterActiveLargeUpload("upload-3");
        fileService.scheduleDirectoryForSynchronization(directoryId,
                new AssetUpdate("completedUploadNoLongerBlocksSynchronization", null, null, "user"));

        boolean awaitingSync = jdbi.withHandle(handle -> handle.attach(DirectoryRepository.class)
                .getDirectory(directoryId)
                .awaitingErdaSync());
        assertThat(awaitingSync).isTrue();
    }

    private static Jdbi localJdbi() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgreSQL.getJdbcUrl());
        dataSource.setUser(postgreSQL.getUsername());
        dataSource.setPassword(postgreSQL.getPassword());
        return Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .registerRowMapper(ConstructorMapper.factory(Directory.class));
    }

    private static long createDirectory(Jdbi jdbi, String assetGuid) {
        return jdbi.inTransaction(handle -> {
            handle.attach(ActiveLargeUploadRepository.class).deleteByAssetGuid(assetGuid);
            DirectoryRepository directoryRepository = handle.attach(DirectoryRepository.class);
            Directory directory = new Directory(null,
                    "/i1/c1/" + assetGuid + "/",
                    "test.dassco.dk",
                    AccessType.WRITE,
                    Instant.now(),
                    10,
                    false,
                    0,
                    List.of(new SharedAsset(null, null, assetGuid, Instant.now())),
                    List.of(new UserAccess(null, null, "user", "token", Instant.now())));
            long directoryId = directoryRepository.insertDirectory(directory);
            handle.attach(SharedAssetRepository.class)
                    .fillBatch(directoryId, List.of(new SharedAsset(null, null, assetGuid, Instant.now())));
            return directoryId;
        });
    }
}
