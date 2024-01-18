package dk.northtech.dasscofileproxy.domain;

import jakarta.annotation.Nullable;
import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

import java.time.Instant;
import java.util.List;

public record Directory(Long directoryId
        , String uri
        , AccessType access
        , Instant creationDatetime
        , @Nullable List<SharedAsset> sharedAssets
        , @Nullable List<UserAccess> userAccess) {

    @JdbiConstructor
    public Directory {
    }

    public Directory(Directory directory, Long directoryId) {
        this(directoryId
                , directory.uri
                , directory.access()
                , directory.creationDatetime
                , directory.sharedAssets
                , directory.userAccess);
    }

    public Directory(Directory sambaServer, List<SharedAsset> sharedAssets, List<UserAccess> userAccess) {
        this(sambaServer.directoryId
                , sambaServer.uri()
                , sambaServer.access()
                , sambaServer.creationDatetime
                , sharedAssets
                , userAccess);
    }


}
