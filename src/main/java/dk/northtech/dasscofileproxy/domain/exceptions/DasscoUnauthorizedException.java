package dk.northtech.dasscofileproxy.domain.exceptions;

import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Exceptions thrown for Dassco Actions (such as assets being locked, workstations out of service, etc")
public class DasscoUnauthorizedException extends DasscoException{
    public DasscoUnauthorizedException() {
    }

    public DasscoUnauthorizedException(String message) {
        super(message);
    }

    public DasscoUnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public int getHttpCode() {
        return 401;
    }

    @Override
    public DaSSCoError getDassCoError() {
        return new DaSSCoError("1.0", DaSSCoErrorCode.UNAUTHORIZED, getMessage());
    }
}
