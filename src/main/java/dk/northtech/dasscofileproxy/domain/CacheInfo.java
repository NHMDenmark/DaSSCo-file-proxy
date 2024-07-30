package dk.northtech.dasscofileproxy.domain;

import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

import java.time.Instant;

public record CacheInfo(String path, Instant creationDatetime, Instant expiration_datetime, FileSyncStatus syncStatus, boolean deleteAfterSync, long sizeBytes, long fileCacheId, long fileId) {
    @JdbiConstructor
    public CacheInfo(String path, Instant creationDatetime, Instant expiration_datetime, FileSyncStatus syncStatus, boolean deleteAfterSync, long sizeBytes, long fileCacheId, long fileId) {
        this.path = path;
        this.creationDatetime = creationDatetime;
        this.expiration_datetime = expiration_datetime;
        this.syncStatus = syncStatus;
        this.deleteAfterSync = deleteAfterSync;
        this.sizeBytes = sizeBytes;
        this.fileCacheId = fileCacheId;
        this.fileId = fileId;
    }

    public CacheInfo(String path, Instant expiration_datetime, long fileId) {
        this(path, null, expiration_datetime, null, false, 0, 0, fileId);
    }
}
