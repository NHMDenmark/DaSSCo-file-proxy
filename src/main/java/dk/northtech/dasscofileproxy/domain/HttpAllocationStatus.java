package dk.northtech.dasscofileproxy.domain;

public enum HttpAllocationStatus {
    DISK_FULL,
    SUCCESS,
    ILLEGAL_STATE,
    UNKNOWN_ERROR,
    UPSTREAM_ERROR,
    INTERNAL_ERROR;
}
