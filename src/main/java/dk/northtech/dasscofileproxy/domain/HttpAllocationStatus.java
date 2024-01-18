package dk.northtech.dasscofileproxy.domain;

public enum HttpAllocationStatus {
    DISK_FULL,
    SUCCESS,
    UNKNOWN_ERROR,
    UPSTREAM_ERROR,
    INTERNAL_ERROR;
}
