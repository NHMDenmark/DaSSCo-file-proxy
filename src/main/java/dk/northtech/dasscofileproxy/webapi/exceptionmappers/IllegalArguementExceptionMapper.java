package dk.northtech.dasscofileproxy.webapi.exceptionmappers;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

public class IllegalArguementExceptionMapper implements ExceptionMapper<IllegalArgumentException> {
    @Override
    public Response toResponse(IllegalArgumentException e) {
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, e.getMessage()))
                .build();
    }
}
