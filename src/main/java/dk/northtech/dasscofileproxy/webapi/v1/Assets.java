package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.io.File;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/assets")
@Tag(name = "Assets", description = "Endpoints related to assets")
@SecurityRequirement(name = "dassco-idp")
public class Assets {
    @Inject
    public FileService fileService;
    @GET
    @Path("/{assetGuid}/files")
    @Operation(summary = "Get List of Asset Files Metadata", description = "Get a list of file metadata associated with an asset")
    // TODO: Roles allowed?
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DasscoFile.class)))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public List<DasscoFile> listFiles(
            @PathParam("assetGuid") String assetGuid
            , @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        return fileService.listFilesByAssetGuid(assetGuid);
    }

//    @GET
//    @Path("/{assetGuid}/status")
//    @Produces(MediaType.APPLICATION_JSON)
//    @Consumes(APPLICATION_JSON)
//    public List<AssetStatusInfo> getStatus(
//            @PathParam("assetGuid") String assetGuid
//            , @Context SecurityContext securityContext) {
//        User user = UserMapper.from(securityContext);
//        return fileService.listFilesByAssetGuid(assetGuid);
//    }
//
//    @GET
//    @Path("/{assetGuid}/status")
//    @Produces(MediaType.APPLICATION_JSON)
//    @Consumes(APPLICATION_JSON)
//    public List<DasscoFile> getStatus(
//            @PathParam("assetGuid") String assetGuid
//            , @Context SecurityContext securityContext) {
//        User user = UserMapper.from(securityContext);
//        return fileService.listFilesByAssetGuid(assetGuid);
//    }
}
