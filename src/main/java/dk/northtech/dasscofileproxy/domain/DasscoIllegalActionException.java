package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Exceptions thrown for Dassco Actions (such as assets being locked, workstations out of service, etc")
public class DasscoIllegalActionException extends RuntimeException {
    public DasscoIllegalActionException() {
    }

    public DasscoIllegalActionException(String message) {
        super(message);
    }

    public DasscoIllegalActionException(String message, Throwable cause) {
        super(message, cause);
    }
}
