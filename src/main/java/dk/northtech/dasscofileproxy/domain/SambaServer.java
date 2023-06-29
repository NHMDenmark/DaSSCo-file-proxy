package dk.northtech.dasscofileproxy.domain;

import jakarta.annotation.Nullable;
import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

import java.time.Instant;
import java.util.List;

public record SambaServer (Long sambaServerId, String sharePath, boolean shared, Integer containerPort
        , AccessType access, Instant creationDatetime, @Nullable List<SharedAsset> sharedAssets, @Nullable List<UserAccess> userAccess) {

    @JdbiConstructor
    public SambaServer {
    }

    public SambaServer(SambaServer sambaServer, Long sambaServerId) {
        this(sambaServerId
                , sambaServer.sharePath
                , sambaServer.shared
                , sambaServer.containerPort
                , sambaServer.access()
                , sambaServer.creationDatetime
                , sambaServer.sharedAssets
                , sambaServer.userAccess);
    }

    public SambaServer(SambaServer sambaServer, List<SharedAsset> sharedAssets, List<UserAccess> userAccess) {
        this(sambaServer.sambaServerId
                , sambaServer.sharePath
                , sambaServer.shared
                , sambaServer.containerPort
                , sambaServer.access()
                , sambaServer.creationDatetime
                , sharedAssets
                , userAccess);
    }


}
