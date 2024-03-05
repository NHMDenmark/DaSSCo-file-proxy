package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.service.*;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.model.AssetStorageAllocation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/shares")
public class HttpShareAPI {
    public static final Logger logger = LoggerFactory.getLogger(HttpShareAPI.class);
    HttpShareService httpShareService;
    SFTPService sftpService;

    @Inject
    public HttpShareAPI(HttpShareService httpShareService, SFTPService sftpService) {
        this.httpShareService = httpShareService;
        this.sftpService = sftpService;
    }



    @POST
    @Path("/assets/{assetGuid}/createShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public HttpInfo createSambaServer(CreationObj creationObj
            , @PathParam("assetGuid") String assetGuid
            , @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        if(creationObj.assets().size() != 1) {
            throw new DasscoIllegalActionException("You may only checkout one asset using this API");
        }
        MinimalAsset minimalAsset = creationObj.assets().getFirst();
        if(!assetGuid.equals(minimalAsset.asset_guid())) {
            throw new IllegalArgumentException("Asset guid in query param doesnt match the one in the provided asset");
        }
        if(creationObj.allocation_mb() == 0) {
            throw new IllegalArgumentException("Allocation cannot be 0");
        }
        return httpShareService.createHttpShare(creationObj, user);

    }

    @POST
    @Path("/assets/{assetGuid}/createShareInternal")
    @RolesAllowed({SecurityRoles.SERVICE, SecurityRoles.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public HttpInfo createSambaServerInternal(CreationObj creationObj, @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        if(creationObj.allocation_mb() == 0) {
            throw new IllegalArgumentException("Allocation cannot be 0");
        }
        return httpShareService.createHttpShareInternal(creationObj, user);

    }

    @POST
    @Path("/assets/{assetGuid}/deleteShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public Response close(@Context SecurityContext securityContext
    , @PathParam("assetGuid") String assetGuid) {
        User user = UserMapper.from(securityContext);
        HttpInfo httpInfo = httpShareService.deleteShare(user, assetGuid);
        return Response.status(httpInfo.http_allocation_status().httpCode).entity(httpInfo).build();

    }

    @POST
    @Path("/assets/{assetGuid}/changeAllocation")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN})
    public HttpInfo updateStorageAllocation(AssetStorageAllocation newAllocation) {
        return httpShareService.allocateStorage(newAllocation);
    }

    @POST
    @Path("/assets/{assetGuid}/synchronize")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN})
    public void synchronize(
            @PathParam("assetGuid") String assetGuid
            , @QueryParam("workstation") String workstation
            , @QueryParam("pipeline") String pipeline
            , @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        sftpService.moveToERDA(new AssetUpdate(assetGuid,workstation,pipeline,user.username));
    }

}
