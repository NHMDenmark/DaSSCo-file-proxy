package dk.northtech.dasscofileproxy.webapi.exceptionmappers;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {
    @Override
    public Response toResponse(NotFoundException e) {
        return DaSSCoErrorResponse.notFound("The requested resource could not be found.");
    }
}
