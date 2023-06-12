package dk.northtech.dasscofileproxy.domain;

import java.time.Instant;

public record UserAccess (Long userAccessId, Long sambaServerId, String username, String token, Instant creationDatetime) {
}
