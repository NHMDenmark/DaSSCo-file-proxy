package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.CacheFileService;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoErrorCode;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
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
import org.apache.commons.io.FileUtils;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/files")
@Tag(name = "Asset Files", description = "Endpoints related to assets' files.")
@SecurityRequirement(name = "dassco-idp")
public class Files {
    private static final Logger logger = LoggerFactory.getLogger(Files.class);
    private final CacheFileService cacheFileService;
    private FileService fileService;
    @Context
    UriInfo uriInfo;

    @Inject
    public Files(CacheFileService cacheFileService, FileService fileService) {
        this.cacheFileService = cacheFileService;
        this.fileService = fileService;
    }


    @GET
    @Path("/assets/{institution}/{collection}/{assetGuid}/{path: .+}")
    public Response getFile(
            @PathParam("institution") String institution
            , @PathParam("collection") String collection
            , @PathParam("assetGuid") String guid
            , @Context SecurityContext securityContext
    ) {
        final String path = uriInfo.getPathParameters().getFirst("path");
        logger.info("Getting file");
        if (securityContext == null) {
            return Response.status(401).build();
        }
        Optional<FileService.FileResult> file = cacheFileService.getFile(institution, collection, guid, path, UserMapper.from(securityContext));
        logger.info("got file");

        if (file.isPresent()) {
//            try {
            FileService.FileResult fileResult = file.get();
            StreamingOutput streamingOutput = output -> {
                fileResult.is().transferTo(output);
                output.flush();
            };

            return Response.status(200)
                    .header("Content-Disposition", "attachment; filename=" + fileResult.filename())
                    .header("Content-Type", new Tika().detect(fileResult.filename())).entity(streamingOutput).build();
//            }
//            finally {
//                try {
//                    file.get().is().close();
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }
//            }
        } else {
            return Response.status(404).build();
        }
    }

    // TODO: CHECK these ones. They were deleted from the original class, maybe I need to replace them!
    @DELETE
    @Path("/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
    // TODO: Same as with the Get, should the path be changed to a @PathParam? Currently it only works in Postman
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
    @Operation(summary = "Delete Asset Files", description = "Deletes all files for an asset based on institution, collection and asset_guid")
    @Produces(APPLICATION_JSON)
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
/*
    @POST
    @Path("/createZipFile/{institution}/{collection}/{assetGuid}")
    @Operation(summary = "Create Zip File", description = "Creates a Zip File with Asset metadata in CSV format and its associated files")
    @Produces(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_PLAIN, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("ZIP File created successfully.")}))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = MediaType.TEXT_PLAIN, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("Error creating ZIP file.")}))
            public Response createZip(@PathParam("institution") String institution,
                            @PathParam("collection") String collection,
                            @PathParam("assetGuid") String assetGuid,
                                      @Context SecurityContext securityContext){

        boolean hasAccess = fileService.checkAccess(assetGuid, UserMapper.from(securityContext));

        if (!hasAccess){
            return Response.status(400).entity(new DaSSCoError("1.0", DaSSCoErrorCode.FORBIDDEN, "User does not have access to create this Zip file")).build();
        }

        FileUploadData fileUploadData = new FileUploadData(assetGuid, institution, collection, assetGuid + ".zip", 0);

        try {
            fileService.createZipFile(fileUploadData.getFilePath());
            return Response.ok().entity("ZIP file created successfully").build();
        } catch (IOException e) {
            return Response.status(400).entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, e.getMessage())).build();
        }
    }
*/
    @GET
    @Path("/downloadZipFile/{institution}/{collection}/{assetGuid}")
    @Operation(summary = "Download Zip File", description = "Downloads zip file associated to an asset_guid. The file needs to be created beforehand by calling /createZipFile/{asset_guid}")
    @ApiResponse(responseCode = "200", description = "Returns the .zip file.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response downloadZip(@PathParam("institution") String institution,
                                @PathParam("collection") String collection,
                                @PathParam("assetGuid") String assetGuid,
                                @Context SecurityContext securityContext){

        boolean hasAccess = fileService.checkAccess(assetGuid, UserMapper.from(securityContext));

        if (!hasAccess){
            return Response.status(400).entity(new DaSSCoError("1.0", DaSSCoErrorCode.FORBIDDEN, "User does not have access to download this Zip file")).build();
        }

        FileUploadData fileUploadData = new FileUploadData(assetGuid, institution, collection, assetGuid + ".zip", 0);
        Optional<FileService.FileResult> getFileResult = fileService.getFile(fileUploadData);
        if (getFileResult.isPresent()){
            FileService.FileResult fileResult = getFileResult.get();
            StreamingOutput streamingOutput = output -> {
                try (InputStream is = fileResult.is()) {
                    is.transferTo(output);
                    output.flush();
                }
            };

            return Response.status(200)
                    .header("Content-Disposition", "attachment; filename=" + fileResult.filename())
                    .header("Content-Type", new Tika().detect(fileResult.filename())).entity(streamingOutput).build();
        }

        return Response.status(400).entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, "Incorrect File or Path")).build();
    }

    // EDITED
    @POST
    @Path("/createCsvFile")
    @Operation(summary = "Create CSV File", description = "Creates a CSV File with Asset metadata")
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_PLAIN, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("CSV File created successfully.")}))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = MediaType.TEXT_PLAIN, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("Error creating CSV file: User does not have access to Asset ['asset-1']")}))
    public Response createCsvFile(@Context SecurityContext securityContext,
                              List<String> assets) {

    return fileService.checkAccess(assets, UserMapper.from(securityContext));
    }

    // NEW
    @GET
    @Path("/getTempFile/{fileName}")
    @Operation(summary = "Get Temporary File", description = "Gets a file from the Temp Folder (.csv or .zip for downloading assets).")
    public Response getTempFile(@PathParam("fileName") String fileName){
        String projectDir = System.getProperty("user.dir");
        java.nio.file.Path tempDir = Paths.get(projectDir, "target", "temp");
        java.nio.file.Path filePath = tempDir.resolve(fileName);

        if (java.nio.file.Files.notExists(filePath)){
            // We'll see:
        }

        if (java.nio.file.Files.notExists(tempDir)){
            // We'll see:
        }

        StreamingOutput streamingOutput = output -> {
            try (InputStream is = java.nio.file.Files.newInputStream(filePath)) {
                is.transferTo(output);
                output.flush();
            } catch (IOException e) {
                throw new RuntimeException("Error reading file", e);
            }
        };

        return Response.ok(streamingOutput)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .build();
    }

    // NEW
    @DELETE
    @Path("/deleteTempFolder")
    @Operation(summary = "Deletes the temp folder, which contains .csv and .zip files from the Query Page and Detailed View")
    @ApiResponse(responseCode = "204", description = "No Content. File has been deleted.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response deleteTempFolder(){

        String projectDir = System.getProperty("user.dir");
        File tempDir = new File(projectDir, "target/temp");

        try {
            if (tempDir.exists()){
                FileUtils.deleteDirectory(tempDir);
                return Response.status(Response.Status.NO_CONTENT).build();
            } else  {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } catch (IOException e) {
            throw new RuntimeException("Error deleting temporary folder" + e.getMessage());
        }
    }
/*
    @DELETE
    @Path("/deleteLocalFiles/{institution}/{collection}/{assetGuid}/{file}")
    @Operation(summary = "Delete Local File", description = "Deletes a file saved in the local machine, such as the generated .csv and .zip files for the Detailed View")
    @ApiResponse(responseCode = "204", description = "No Content. File has been deleted.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response deleteLocalFiles(@PathParam("institution") String institution,
                                     @PathParam("collection") String collection,
                                     @PathParam("assetGuid") String assetGuid,
                                     @PathParam("file") String file){
        FileUploadData fileUploadData = new FileUploadData(assetGuid, institution, collection, file, 0);
        boolean isDeleted = fileService.deleteLocalFiles(fileUploadData.getFilePath(), file);

        if (isDeleted){
            return Response.status(Response.Status.NO_CONTENT).build();
        } else {
            return Response.status(400).entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, "Incorrect File or Path")).build();
        }
    }

*/
}
