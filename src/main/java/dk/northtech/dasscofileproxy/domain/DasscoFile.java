package dk.northtech.dasscofileproxy.domain;

import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

public record DasscoFile(Long fileId, String assetGuid, String path, long sizeBytes, long crc, boolean deleteAfterSync, FileSyncStatus syncStatus) {
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
