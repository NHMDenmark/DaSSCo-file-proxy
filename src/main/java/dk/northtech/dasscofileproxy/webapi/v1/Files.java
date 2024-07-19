package dk.northtech.dasscofileproxy.webapi.v1;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

@Path("/files")
@Tag(name = "Asset Files", description = "Endpoints related to assets' files.")
@SecurityRequirement(name = "dassco-idp")
public class Files {

    @Context
    UriInfo uriInfo;
    
    @GET
    @Path("/assets/{institution}/{collection}/{assetGuid}/{path: .+}")
    public void getFile(
            @PathParam("institution") String institution
            , @PathParam("collection") String collection
            , @PathParam("assetGuid") String guid
            , @Context SecurityContext securityContext
            ) {
        final String path = uriInfo.getPathParameters().getFirst("path");

    }
}
