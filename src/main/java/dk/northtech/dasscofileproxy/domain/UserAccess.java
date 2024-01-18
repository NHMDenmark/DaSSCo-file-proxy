package dk.northtech.dasscofileproxy.domain;

import java.time.Instant;

public record UserAccess (Long userAccessId, Long directoryId, String username, String token, Instant creationDatetime) {
}
