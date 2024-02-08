package dk.northtech.dasscofileproxy.domain;

import jakarta.annotation.Nullable;
import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

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
        , @Nullable List<SharedAsset> sharedAssets
        , @Nullable List<UserAccess> userAccess) {

    @JdbiConstructor
    public Directory {
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
                , directory.sharedAssets
                , directory.userAccess);
    }

    public Directory(Directory sambaServer, List<SharedAsset> sharedAssets, List<UserAccess> userAccess) {
        this(sambaServer.directoryId
                , sambaServer.uri()
                , sambaServer.node_host
                , sambaServer.access()
                , sambaServer.creationDatetime
                , sambaServer.allocatedStorageMb()
                , sambaServer.awaitingErdaSync
                , sambaServer.erdaSyncAttempts
                , sharedAssets
                , userAccess);
    }


}
