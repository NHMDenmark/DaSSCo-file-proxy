package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.service.*;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.model.AssetStorageAllocation;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Shares", description = "Endpoints related to assets' allocation")
@SecurityRequirement(name = "dassco-idp")
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
    @Operation(summary = "Open Share", description = "Creates a share for the asset.")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = HttpInfo.class)))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
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
        if(creationObj.allocation_mb() <= 0) {
            throw new IllegalArgumentException("Allocation must be a positive integer");
        }
        return httpShareService.createHttpShare(creationObj, user);

    }

    @POST
    @Path("/assets/{assetGuid}/createShareInternal")
    @Operation(summary = "Create Share (Internal)", description = "Creates a share for the asset")
    @RolesAllowed({SecurityRoles.SERVICE, SecurityRoles.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = HttpInfo.class)))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public HttpInfo createSambaServerInternal(CreationObj creationObj, @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        if(creationObj.allocation_mb() <= 0) {
            throw new IllegalArgumentException("Allocation must be a positive integer");
        }
        return httpShareService.createHttpShareInternal(creationObj, user);

    }

    @DELETE
    @Path("/assets/{assetGuid}/deleteShare")
    @Operation(summary = "Delete Share", description = "Deletes a share from the asset.")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.SERVICE,SecurityRoles.USER, SecurityRoles.ADMIN})
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = HttpInfo.class)))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = HttpInfo.class)))
    public Response close(@Context SecurityContext securityContext
    , @PathParam("assetGuid") String assetGuid) {
        User user = UserMapper.from(securityContext);
        HttpInfo httpInfo = httpShareService.deleteShare(user, assetGuid);
        return Response.status(httpInfo.http_allocation_status().httpCode).entity(httpInfo).build();

    }

    @POST
    @Path("/assets/{assetGuid}/changeAllocation")
    @Operation(summary = "Change Allocation", description = "Changes allocation for an asset")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = HttpInfo.class)))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = HttpInfo.class)))
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN})
    public HttpInfo updateStorageAllocation(AssetStorageAllocation newAllocation) {
        return httpShareService.allocateStorage(newAllocation);
    }

    @POST
    @Path("/assets/{assetGuid}/synchronize")
    @Operation(summary = "Synchronize ERDA", description = "Synchronizes the asset with ERDA") // I think.
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "204", description = "No Content")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
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
