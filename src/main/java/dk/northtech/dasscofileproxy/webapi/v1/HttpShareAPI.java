package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoIllegalActionException;
import dk.northtech.dasscofileproxy.service.*;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.model.AssetStorageAllocation;
import io.swagger.v3.oas.annotations.Hidden;
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

import java.util.List;

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

    @GET
    @Path("/")
    @Operation(summary = "Open Share", description = "Here you can open a share of an existing asset. The post body consists of a list of assets to be shared and a list of usernames of users that should have access to the share. The amount of space needed to be allocated also needs to be specified. The list of assets can only contain one asset when using this endpoint.")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = HttpInfo.class)))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public List<HttpShareService.Share> listShares(@Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
      return httpShareService.listShares();


    }
    @POST
    @Path("/assets/{assetGuid}/createShare")
    @Operation(summary = "Open Share", description = "Here you can open a share of an existing asset. The post body consists of a list of assets to be shared and a list of usernames of users that should have access to the share. The amount of space needed to be allocated also needs to be specified. The list of assets can only contain one asset when using this endpoint.")
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

    // This API doesnt check if the asset exists because the asset service will first persist the asset once the share is open.
    // This is only for internal use.
    @Hidden
    @POST
    @Path("/assets/{assetGuid}/createShareInternal")
    @Operation(summary = "Create Share (Internal)", description = "Creates a share for the asset, doesnt check if asset exists before creating share")
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
    @Operation(summary = "Delete Share", description = "This service deletes a share and all files in the share without synchronizing ERDA. Files already persisted in ERDA will not be deleted.")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({ SecurityRoles.SERVICE, SecurityRoles.USER, SecurityRoles.ADMIN })
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
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public HttpInfo updateStorageAllocation(AssetStorageAllocation newAllocation) {
        return httpShareService.allocateStorage(newAllocation);
    }

    @POST
    @Path("/assets/{assetGuid}/synchronize")
    @Operation(summary = "Synchronize ERDA", description = "Close for further uploads to the asset, and schedules the asset files for ERDA. Once this has been called the asset is 'closed' for now and awaits upload to ERDA.") // I think.
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "204", description = "No Content")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public void synchronize(
            @PathParam("assetGuid") String assetGuid
            , @QueryParam("workstation") String workstation
            , @QueryParam("pipeline") String pipeline
            , @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        sftpService.moveToERDA(new AssetUpdate(assetGuid,workstation,pipeline,user.username));
    }

}
