package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.tika.Tika;
import org.checkerframework.checker.units.qual.A;
import org.checkerframework.checker.units.qual.C;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/assetfiles")
@Tag(name = "Asset Files", description = "Endpoints related to assets' files.")
@SecurityRequirement(name = "dassco-idp")
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
    @Operation(summary = "Upload File", description = "Uploads a file. Requires institution, collection, asset_guid, crc and file size (in mb).\n\n" +
                                                        "Can be called multiple times to upload multiple files to the same asset. If the files are called the same, the file will be overwritten.")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = FileUploadResult.class)))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
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
    // TODO: Should we add the Path as a PathParam in the Docs? At the moment it only works via Postman, because we are using uriInfo instead of just calling @PathParam
    @Operation(summary = "Get Asset File by path", description = "Get an asset file based on institution, collection, asset_guid and path to the file")
//    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "Returns the file.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
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
    @Operation(summary = "Get List of Asset Files", description = "Get a list of files based on institution, collection and asset_guid")
    // TODO: Roles allowed?
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("[\"test-institution/test-collection/nt_asset_19/example.jpg\", \"test-institution/test-collection/nt_asset_19/example2.jpg\"]")}))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
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
    // TODO: Same as with the Get, should we add the path as @PathParam?
//    @Produces(MediaType.APPLICATION_JSON)
//    @Consumes(APPLICATION_JSON)
    @Operation(summary = "Delete Asset File by path", description = "Delete resource at the given path. If the resource is a directory, it will be deleted along its content. If the resource is the base directory for an asset the directory will not be deleted, only the content.")
    @ApiResponse(responseCode = "204", description = "No Content. File has been deleted.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
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
    @Operation(summary = "Delete Asset File", description = "Deletes an asset file based on institution, collection and asset_guid")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "204", description = "No Content. File has been deleted.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
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
