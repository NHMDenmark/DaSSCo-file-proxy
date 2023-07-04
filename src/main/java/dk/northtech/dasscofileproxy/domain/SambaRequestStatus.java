package dk.northtech.dasscofileproxy.domain;

public enum SambaRequestStatus {
    OK_OPEN
    , OK_DISCONNECTED
    , OK_CLOSED
    , NO_PORT_AVAILABLE
    , SMB_FAILED
    , ERDA_SYNC_FAILED
    , UPSTREAM_ERROR
    , INTERNAL_ERROR
}
