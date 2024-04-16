package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Privilege required to perform an action", example = "WRITE")
public enum AccessType {
    READ,
    WRITE,
    ADMIN
}
