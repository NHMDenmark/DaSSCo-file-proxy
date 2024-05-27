package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Possible HTTP Status responses for allocation", example = "DISK_FULL")
public enum HttpAllocationStatus {
    DISK_FULL(403),
    SUCCESS(200),
    BAD_REQUEST(400),
    UNKNOWN_ERROR(500),
    UPSTREAM_ERROR(503),
    SHARE_NOT_FOUND(404),
    INTERNAL_ERROR(500),
    ;
    public final int httpCode;

    private HttpAllocationStatus(int httpCode) {
        this.httpCode = httpCode;
    }
}
