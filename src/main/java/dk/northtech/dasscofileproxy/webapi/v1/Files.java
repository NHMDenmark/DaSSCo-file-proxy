package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.io.File;
import java.io.InputStream;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/files")
public class Files {
    private FileService fileService;

    @Inject
    public Files(FileService fileService) {
        this.fileService = fileService;
    }

    @Context
    UriInfo uriInfo;
    @PUT
    @Path("/shares/{shareId}/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public Response createSambaServer(
            @PathParam("shareId") long shareId
            , @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @QueryParam("crc") long crc
            , @QueryParam("file_size_mb") int fileSize
            , @Context SecurityContext securityContext
            , InputStream file) {
        User user = UserMapper.from(securityContext);
        if(fileSize == 0) {
            throw new IllegalArgumentException("file_size_mb cannot be 0");
        }
        if(crc == 0) {
            throw new IllegalArgumentException("crc cannot be 0");
        }
        final String path
                = uriInfo.getPathParameters().getFirst("paths");
        FileUploadData fileUploadData = new FileUploadData(assetGuid, institutionName, collectionName, shareId, path, fileSize);
        FileUploadResult upload = fileService.upload(file, crc, fileUploadData);
        return Response.status(upload.getResponseCode()).entity(upload).build();
    }
}
