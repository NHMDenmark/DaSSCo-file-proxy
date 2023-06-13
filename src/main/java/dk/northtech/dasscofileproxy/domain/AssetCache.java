package dk.northtech.dasscofileproxy.domain;

import java.time.LocalDateTime;

public record AssetCache (
    Long assetCacheId,
    String assetPath,
    long fileSize,
    LocalDateTime expirationDatetime,
    LocalDateTime creationDatetime

){}
