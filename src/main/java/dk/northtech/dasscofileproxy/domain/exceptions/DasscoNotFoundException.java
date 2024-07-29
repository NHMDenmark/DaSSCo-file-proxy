package dk.northtech.dasscofileproxy.domain.exceptions;

import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoErrorCode;

public class DasscoNotFoundException extends DasscoException {
    public DasscoNotFoundException(String message) {
        super(message);
    }

    public DasscoNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
    @Override
    public int getHttpCode() {
        return 404;
    }

    @Override
    public DaSSCoError getDassCoError() {
        return new DaSSCoError("1.0", DaSSCoErrorCode.NOT_FOUND, getMessage());
    }
}
