package dk.northtech.dasscofileproxy.domain.exceptions;

import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoErrorCode;

public class DasscoInternalErrorException extends DasscoException {
    public DasscoInternalErrorException() {
    }

    public DasscoInternalErrorException(String message) {
        super(message);
    }

    public DasscoInternalErrorException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public int getHttpCode() {
        return 500;
    }

    @Override
    public DaSSCoError getDassCoError() {
        return new DaSSCoError("1.0", DaSSCoErrorCode.INTERNAL_ERROR, getMessage());
    }
}
