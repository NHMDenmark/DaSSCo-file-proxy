package dk.northtech.dasscofileproxy.domain;

import jakarta.annotation.Nullable;

public record AssetSmbRequest(@Nullable String shareName, @Nullable MinimalAsset minimalAsset) {

}
