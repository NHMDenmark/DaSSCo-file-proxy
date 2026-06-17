package dk.northtech.dasscofileproxy.service;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

@Testcontainers
class FileRepositoryAssetBundleSizeCalculatorTest {
    @Container
    static PostgreSQLContainer<?> postgreSQL = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("dassco_file_proxy")
            .withUsername("dassco_file_proxy")
            .withPassword("dassco_file_proxy");

    @BeforeEach
    void setUp() {
        localJdbi().useHandle(handle -> {
            handle.execute("DROP TABLE IF EXISTS file");
            handle.execute("""
                    CREATE TABLE file(
                        file_id bigserial PRIMARY KEY,
                        asset_guid text NOT NULL,
                        size_bytes bigint NOT NULL,
                        path text NOT NULL,
                        crc bigint NOT NULL,
                        delete_after_sync boolean NOT NULL DEFAULT false,
                        sync_status text NOT NULL DEFAULT 'NEW_FILE',
                        mime_type text,
                        has_thumbnail boolean NOT NULL DEFAULT false
                    )
                    """);
        });
    }

    @Test
    void totalSizeBytes_sumsOnlySelectedAssetsAndIgnoresRowsMarkedForDeletion() {
        Jdbi jdbi = localJdbi();
        insertFile(jdbi, "asset-1", "/asset-1/front.tif", 8_000_000_000L, false);
        insertFile(jdbi, "asset-1", "/asset-1/back.tif", 5_000_000_000L, false);
        insertFile(jdbi, "asset-2", "/asset-2/front.tif", 7_000_000_000L, false);
        insertFile(jdbi, "asset-2", "/asset-2/deleted.tif", 99_000_000_000L, true);
        insertFile(jdbi, "asset-not-selected", "/asset-not-selected/front.tif", 100_000_000_000L, false);
        FileRepositoryAssetBundleSizeCalculator calculator = new FileRepositoryAssetBundleSizeCalculator(jdbi);

        long totalSizeBytes = calculator.totalSizeBytes(List.of("asset-1", "asset-1", "asset-2"));

        assertThat(totalSizeBytes).isEqualTo(20_000_000_000L);
    }

    @Test
    void totalSizeBytes_returnsZeroWhenSelectedAssetsHaveNoFileRows() {
        FileRepositoryAssetBundleSizeCalculator calculator = new FileRepositoryAssetBundleSizeCalculator(localJdbi());

        long totalSizeBytes = calculator.totalSizeBytes(List.of("missing-asset"));

        assertThat(totalSizeBytes).isEqualTo(0L);
    }

    private static void insertFile(Jdbi jdbi, String assetGuid, String path, long sizeBytes, boolean deleteAfterSync) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO file(asset_guid, size_bytes, path, crc, delete_after_sync)
                        VALUES (:assetGuid, :sizeBytes, :path, 123, :deleteAfterSync)
                        """)
                .bind("assetGuid", assetGuid)
                .bind("sizeBytes", sizeBytes)
                .bind("path", path)
                .bind("deleteAfterSync", deleteAfterSync)
                .execute());
    }

    private static Jdbi localJdbi() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgreSQL.getJdbcUrl());
        dataSource.setUser(postgreSQL.getUsername());
        dataSource.setPassword(postgreSQL.getPassword());
        return Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin());
    }
}
