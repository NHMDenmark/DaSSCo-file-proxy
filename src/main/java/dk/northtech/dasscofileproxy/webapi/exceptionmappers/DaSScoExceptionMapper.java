package dk.northtech.dasscofileproxy.webapi.exceptionmappers;

import dk.northtech.dasscofileproxy.domain.exceptions.DasscoException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

public class DaSScoExceptionMapper implements ExceptionMapper<DasscoException> {
    @Override
    public Response toResponse(DasscoException e) {
        return Response.status(e.getHttpCode()).entity(e.getDassCoError()).build();
    }
}
