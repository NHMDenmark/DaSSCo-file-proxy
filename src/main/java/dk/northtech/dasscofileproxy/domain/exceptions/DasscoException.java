package dk.northtech.dasscofileproxy.domain.exceptions;

import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import javassist.bytecode.ExceptionTable;

public abstract class DasscoException extends RuntimeException {
    public DasscoException(String message) {
        super(message);
    }

    protected DasscoException() {
    }

    public DasscoException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract int getHttpCode();
    public abstract DaSSCoError getDassCoError();
}
