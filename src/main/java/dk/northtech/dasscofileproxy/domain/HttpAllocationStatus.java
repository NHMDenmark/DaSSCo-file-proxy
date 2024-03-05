package dk.northtech.dasscofileproxy.domain;

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
