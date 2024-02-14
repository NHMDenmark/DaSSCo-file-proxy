package dk.northtech.dasscofileproxy.domain;

import jakarta.annotation.Nullable;
import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

import java.io.File;
import java.time.Instant;
import java.util.List;

public record Directory(Long directoryId
        , String uri
        , String node_host
        , AccessType access
        , Instant creationDatetime
        , int allocatedStorageMb
        , boolean awaitingErdaSync
        , int erdaSyncAttempts
        , String syncUser
        , String syncWorkstation
        , String syncPipeline
        , @Nullable List<SharedAsset> sharedAssets
        , @Nullable List<UserAccess> userAccess) {

    @JdbiConstructor
    public Directory {
    }

    public Directory(Long directoryId, String uri, String node_host, AccessType access, Instant creationDatetime, int allocatedStorageMb, boolean awaitingErdaSync, int erdaSyncAttempts, List<SharedAsset> sharedAssets, List<UserAccess> userAccess) {
        this(directoryId, uri, node_host, access, creationDatetime, allocatedStorageMb, awaitingErdaSync, erdaSyncAttempts, null, null, null, sharedAssets, userAccess);
    }

    public Directory(Directory directory, Long directoryId) {
        this(directoryId
                , directory.uri
                , directory.node_host
                , directory.access()
                , directory.creationDatetime
                , directory.allocatedStorageMb
                , directory.awaitingErdaSync
                , directory.erdaSyncAttempts
                , directory.syncUser
                , directory.syncWorkstation
                , directory.syncPipeline
                , directory.sharedAssets
                , directory.userAccess);
    }
}
