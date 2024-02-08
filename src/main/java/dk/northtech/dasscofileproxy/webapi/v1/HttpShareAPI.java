package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.service.DockerService;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.service.HttpShareService;
import dk.northtech.dasscofileproxy.service.SambaServerService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.model.AssetStorageAllocation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/shares")
public class HttpShareAPI {
    public static final Logger logger = LoggerFactory.getLogger(HttpShareAPI.class);
    HttpShareService httpShareService;

    @Inject
    public HttpShareAPI(HttpShareService httpShareService) {
        this.httpShareService = httpShareService;
    }

    @POST
    @Path("/createShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public HttpInfo createSambaServer(CreationObj creationObj, @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        return httpShareService.createHttpShare(creationObj, user);

    }

    @POST
    @Path("/assets/changeAllocation")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN, SecurityRoles.ADMIN})
    public HttpInfo updateStorageAllocation(AssetStorageAllocation newAllocation) {
        return httpShareService.allocateStorage(newAllocation);
    }

    @POST
    @Path("/disconnectShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN, SecurityRoles.ADMIN})
    public SambaInfo disconnectSambaServer(AssetSmbRequest assetSmbRequest, @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        return null;
//        return httpShareService..disconnect(assetSmbRequest, user);
    }
}
