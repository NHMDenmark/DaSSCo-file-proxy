package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoErrorCode;
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
import org.apache.commons.io.FileUtils;
import org.apache.tika.Tika;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/assetfiles")
@Tag(name = "Asset Files", description = "Endpoints related to assets' files.")
@SecurityRequirement(name = "dassco-idp")
public class AssetFiles {
    private FileService fileService;

    @Inject
    public AssetFiles(FileService fileService) {
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
    // TODO: Should the path remain like this? This endpoint only works in Postman in its current state, and not in the Documentation Page. •
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

            boolean hasAccess = fileService.checkAccess(assetGuid, UserMapper.from(securityContext));

            if (!hasAccess){
                return Response.status(400).entity(new DaSSCoError("1.0", DaSSCoErrorCode.FORBIDDEN, "User does not have access to download this file")).build();
            }

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
            fileService.createZipFile(fileUploadData.getAssetFilePath());
            return Response.ok().entity("ZIP file created successfully").build();
        } catch (IOException e) {
            return Response.status(400).entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, e.getMessage())).build();
        }
    }

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
        boolean isDeleted = fileService.deleteLocalFiles(fileUploadData.getAssetFilePath(), file);

        if (isDeleted){
            return Response.status(Response.Status.NO_CONTENT).build();
        } else {
            return Response.status(400).entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, "Incorrect File or Path")).build();
        }
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
}