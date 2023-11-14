package dk.northtech.dasscofileproxy.domain;

import jakarta.annotation.Nullable;

public record AssetUpdateRequest(@Nullable String shareName, @Nullable MinimalAsset minimalAsset, @Nullable String workstation, @Nullable String pipeline, @Nullable String digitizer) {

}
