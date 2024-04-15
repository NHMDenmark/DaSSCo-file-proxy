package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.io.File;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/assets")
@SecurityRequirement(name = "dassco-idp")
public class Assets {
    @Inject
    public FileService fileService;
    @GET
    @Path("/{assetGuid}/files")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
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
