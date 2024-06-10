package dk.northtech.dasscofileproxy.domain.exceptions;

import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Exceptions thrown for Dassco Actions (such as assets being locked, workstations out of service, etc")
public class DasscoIllegalActionException extends DasscoException{
    public DasscoIllegalActionException() {
    }

    public DasscoIllegalActionException(String message) {
        super(message);
    }

    public DasscoIllegalActionException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public int getHttpCode() {
        return 400;
    }

    @Override
    public DaSSCoError getDassCoError() {
        return new DaSSCoError("1.0", DaSSCoErrorCode.FORBIDDEN, getMessage());
    }
}
