package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.tika.Tika;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/assetfiles")
public class Files {
    private FileService fileService;

    @Inject
    public Files(FileService fileService) {
        this.fileService = fileService;
    }

    @Context
    UriInfo uriInfo;

    @PUT
    @Path("/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response putFile(
            @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @QueryParam("crc") long crc
            , @QueryParam("file_size_mb") int fileSize
            , @Context SecurityContext securityContext
            , InputStream file) {
        User user = UserMapper.from(securityContext);
        if (fileSize == 0) {
            throw new IllegalArgumentException("file_size_mb cannot be 0");
        }
        if (crc == 0) {
            throw new IllegalArgumentException("crc cannot be 0");
        }
        final String path
                = uriInfo.getPathParameters().getFirst("path");
        FileUploadData fileUploadData = new FileUploadData(assetGuid, institutionName, collectionName, path, fileSize);
        FileUploadResult upload = fileService.upload(file, crc, fileUploadData);
        return Response.status(upload.getResponseCode()).entity(upload).build();
    }


    @GET
    @Path("/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
//    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Consumes(APPLICATION_JSON)
    public Response getFile(
            @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @Context SecurityContext securityContext
    ) {
        final String path
                = uriInfo.getPathParameters().getFirst("path");
        User user = UserMapper.from(securityContext);
        FileUploadData fileUploadData = new FileUploadData(assetGuid, institutionName, collectionName, path, 0);
        Optional<FileService.FileResult> getFileResult = fileService.getFile(fileUploadData);
        if (getFileResult.isPresent()) {
            FileService.FileResult fileResult = getFileResult.get();
            StreamingOutput streamingOutput = output -> {
                fileResult.is().transferTo(output);
                output.flush();
            };

            return Response.status(200)
                    .header("Content-Disposition", "attachment; filename=" + fileResult.filename())
                    .header("Content-Type", new Tika().detect(fileResult.filename())).entity(streamingOutput).build();
        }
        return Response.status(404).build();
    }

    @GET
    @Path("/{institutionName}/{collectionName}/{assetGuid}/")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public List<String> listFiles(
            @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @Context SecurityContext securityContext
    ) {
        User user = UserMapper.from(securityContext);
        List<String> links = fileService.listAvailableFiles(new FileUploadData(assetGuid, institutionName, collectionName, null, 0));
        return links;
    }

    @DELETE
    @Path("/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
//    @Produces(MediaType.APPLICATION_JSON)
//    @Consumes(APPLICATION_JSON)
    public Response deletefile(
            @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        final String path
                = uriInfo.getPathParameters().getFirst("path");
        boolean deleted = fileService.deleteFile(new FileUploadData(assetGuid, institutionName, collectionName, path, 0));
        return Response.status(deleted ? 204 : 404).build();
    }

    //Delete all files under an azzet
    @DELETE
    @Path("/{institutionName}/{collectionName}/{assetGuid}/")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public Response deleteAsset(
            @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        boolean deleted = fileService.deleteFile(new FileUploadData(assetGuid, institutionName, collectionName, null, 0));
        return Response.status(deleted ? 204 : 404).build();
    }
}
