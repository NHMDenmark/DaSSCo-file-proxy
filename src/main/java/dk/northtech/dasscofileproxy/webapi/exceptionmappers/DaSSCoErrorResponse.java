package dk.northtech.dasscofileproxy.webapi.exceptionmappers;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public final class DaSSCoErrorResponse {
    private DaSSCoErrorResponse() {
    }

    public static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(new DaSSCoError("1.0", DaSSCoErrorCode.NOT_FOUND, message))
                .build();
    }
}
