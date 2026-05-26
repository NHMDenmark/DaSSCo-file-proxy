package dk.northtech.dasscofileproxy.domain.exceptions;

import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoErrorCode;

public class DasscoConflictException extends DasscoException {
    public DasscoConflictException(String message) {
        super(message);
    }

    @Override
    public int getHttpCode() {
        return 409;
    }

    @Override
    public DaSSCoError getDassCoError() {
        return new DaSSCoError("1.0", DaSSCoErrorCode.CONFLICT, getMessage());
    }
}
