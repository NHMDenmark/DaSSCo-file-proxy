package dk.northtech.dasscofileproxy.domain;

import jakarta.annotation.Nullable;

public record AssetUpdate( String assetGuid, @Nullable String workstation, @Nullable String pipeline, @Nullable String digitiser) {

}
