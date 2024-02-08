package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.io.File;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/assets")
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
}
