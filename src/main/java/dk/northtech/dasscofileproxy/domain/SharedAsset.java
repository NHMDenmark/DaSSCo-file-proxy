package dk.northtech.dasscofileproxy.domain;

import java.time.Instant;

public record SharedAsset (Long sharedAssetId, Long directoryId, String assetGuid, Instant creationDatetime) {

}
