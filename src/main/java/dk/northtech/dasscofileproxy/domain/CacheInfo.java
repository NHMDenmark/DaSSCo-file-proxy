package dk.northtech.dasscofileproxy.domain;

import java.time.Instant;

public class CacheInfo {
    private String path;
    private Instant created;
    private Instant expires;
    private FileSyncStatus fileSyncStatus;
    private boolean deleteAfterSync;
    private long size;
    private long fileCacheId;
}
