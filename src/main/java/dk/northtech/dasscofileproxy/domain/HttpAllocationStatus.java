package dk.northtech.dasscofileproxy.domain;

public enum HttpAllocationStatus {
    DISK_FULL,
    SUCCESS,
    BAD_REQUEST,
    UNKNOWN_ERROR,
    UPSTREAM_ERROR,
    SHARE_NOT_FOUND,
    INTERNAL_ERROR,
    ;
}
