package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.SecurityRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/hello")
public class HelloWorld {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    //@RolesAllowed({SecurityRoles.ADMIN, SecurityRoles.DEVELOPER})
    public String getAssets() {
        return "HelloWorld";
    }

}
