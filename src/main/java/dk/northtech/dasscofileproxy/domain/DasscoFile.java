package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

public record DasscoFile(
        @Schema(description = "Id of the File", example = "")
        Long fileId,
        @Schema(description = "The Global Unique Identifier generated for each asset", example = "ti-a01-202305241657")
        String assetGuid,
        @Schema(description = "Path to the File relative from the asset folder", example = "assetfiles/test-institution/test-collection/nt_asset_19/")
        String path,
        @Schema(description = "Size, in bytes", example = "1024")
        long sizeBytes,
        @Schema(description = "Cyclic Redundancy Check, used to verify if the file was transferred correctly. Returns 507 if there is a mismatch between the file checksum and the uploaded file checksum.", example = "123")
        long crc,
        @Schema(description = "Indicates if it should be deleted after Sync", example = "false")
        boolean deleteAfterSync,
        @Schema(description = "Sync status of the file", example = "SYNCHRONIZED")
        FileSyncStatus syncStatus) {
    @JdbiConstructor
    public DasscoFile(Long fileId, String assetGuid, String path, long sizeBytes, long crc, boolean deleteAfterSync, FileSyncStatus syncStatus) {
        this.fileId = fileId;
        this.assetGuid = assetGuid;
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.crc = crc;
        this.deleteAfterSync = deleteAfterSync;
        this.syncStatus = syncStatus;
    }

    public DasscoFile(Long fileId, String assetGuid, String path, long sizeBytes, long crc, FileSyncStatus fileSyncStatus) {
        this(fileId, assetGuid, path, sizeBytes, crc, false, fileSyncStatus);
    }
}
