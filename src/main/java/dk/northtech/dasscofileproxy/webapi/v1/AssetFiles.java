package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.domain.SecurityRoles;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.CacheFileService;
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
import jakarta.annotation.security.RolesAllowed;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static jakarta.ws.rs.core.MediaType.*;

@Path("/assetfiles")
@Tag(name = "Asset Files", description = "Endpoints related to assets' files.")
@SecurityRequirement(name = "dassco-idp")
public class AssetFiles {
    private FileService fileService;
    private CacheFileService cacheFileService;
    private final ShareConfig shareConfig;
    private static final Logger logger = LoggerFactory.getLogger(AssetFiles.class);
    @Inject
    public AssetFiles(FileService fileService, CacheFileService cacheFileService, ShareConfig shareConfig) {
        this.fileService = fileService;
        this.cacheFileService = cacheFileService;
        this.shareConfig = shareConfig;
    }

    @Context
    UriInfo uriInfo;

    @PUT
    @Path("/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
    @Operation(summary = "Upload File", description = "Uploads a file. Requires institution, collection, asset_guid, crc and file size (in mb).\n\n" +
                                                        "Can be called multiple times to upload multiple files to the same asset. If the files are called the same, the file will be overwritten.")
    @Produces(MediaType.APPLICATION_JSON)
    //@Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = FileUploadResult.class)))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response putFile(
            @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @QueryParam("crc") long crc
            , @QueryParam("file_size_mb") int fileSize
            , @Context SecurityContext securityContext
            , @Context HttpHeaders httpHeaders
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
        String contentType = httpHeaders.getHeaderString("Content-Type");
        logger.info("Got Content-Type {}", contentType);
        if(contentType == null) {
            contentType = new Tika().detect(path);
        }
        FileUploadData fileUploadData = new FileUploadData(assetGuid, institutionName, collectionName, path, fileSize,contentType);
        FileUploadResult upload = fileService.upload(file, crc, fileUploadData);
        return Response.status(upload.getResponseCode()).entity(upload).build();
    }


    @GET
    @Path("/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
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
        FileUploadData fileUploadData = new FileUploadData(assetGuid, institutionName, collectionName, path, 0, null);
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
            String contentType = fileResult.mime_type() != null ? fileResult.mime_type() :  new Tika().detect(fileResult.filename());
            return Response.status(200)
                    .header("Content-Disposition", "attachment; filename=" + fileResult.filename())
                    .header("Content-Type", contentType).entity(streamingOutput).build();
        }
        return Response.status(404).build();
    }

    @GET
    @Path("/{institutionName}/{collectionName}/{assetGuid}/")
    @Operation(summary = "Get List of Asset Files that have been checked out", description = "Get a list of files for a given asset with an open share")
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
        List<String> links = fileService.listAvailableFiles(new FileUploadData(assetGuid, institutionName, collectionName, null, 0,null));
        return links;
    }

    @DELETE
    @Path("/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
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
        boolean deleted = fileService.deleteFile(new FileUploadData(assetGuid, institutionName, collectionName, path, 0,null));
        return Response.status(deleted ? 204 : 404).build();
    }

    //Delete all files under an asset
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
        boolean deleted = fileService.deleteFile(new FileUploadData(assetGuid, institutionName, collectionName, null, 0,null));
        return Response.status(deleted ? 204 : 404).build();
    }

    @POST
    @Path("/createZipFile/{guid}")
    @Operation(summary = "Create Zip File", description = """
    Takes a list of Asset Guids, saves the associated files in the temp folder and zips both the images and the .csv with metadata.
    Used by the query page in the frontend in connection with downloading zip file with assets.
    """)
    @Consumes(APPLICATION_JSON)
    @Produces(TEXT_PLAIN)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = TEXT_PLAIN, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("ZIP File created successfully.")}))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = TEXT_PLAIN, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("Error creating ZIP file.")}))
    public Response createZip(@Context SecurityContext securityContext,
                                      List<String> assets,
                              @PathParam("guid") String guid){

        var user = UserMapper.from(securityContext);
        var dasscoFiles = fileService.getDasscoFiles(assets, user, guid);
        if (!dasscoFiles.isEmpty()) {
            List<String> assetFiles = dasscoFiles.stream().map(DasscoFile::path).collect(Collectors.toList());
            cacheFileService.saveFilesTempFolder(assetFiles, user, guid);
        }
        try {
            fileService.createZipFile(guid);
            return Response.status(200).entity(guid).build();
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
        return Response.status(500).entity("There was an error downloading the files").build();
//        return fileService.checkAccessCreateZip(assets, UserMapper.from(securityContext), guid);
    }

    @POST
    @Path("/createCsvFile")
    @Operation(summary = "Create CSV File", description = "Creates a CSV File with Asset metadata in the Temp folder, used by the query page in the frontend in connection with dowloading csv files.")
    @Produces(TEXT_PLAIN)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = TEXT_PLAIN, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("CSV File created successfully.")}))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = TEXT_PLAIN, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("Error creating CSV file: User does not have access to Asset ['asset-1']")}))
    public Response createCsvFile(@Context SecurityContext securityContext,
                                  List<String> assets) {

        return fileService.checkAccessCreateCSV(assets, UserMapper.from(securityContext));
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
        FileUploadData fileUploadData = new FileUploadData(assetGuid, institution, collection, file, 0,null);
        boolean isDeleted = fileService.deleteLocalFiles(fileUploadData.getAssetFilePath(), file);

        if (isDeleted){
            return Response.status(Response.Status.NO_CONTENT).build();
        } else {
            return Response.status(400).entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, "Incorrect File or Path")).build();
        }
    }

    @GET
    @Path("/getTempFile/{guid}/{fileName}")
    @Operation(summary = "Get Temporary File", description = "Gets a file from the Temp Folder (.csv or .zip for downloading assets) used on the query page in the frontend.")
    public Response getTempFile(@PathParam("guid") String guid, @PathParam("fileName") String fileName){
        String basePath = shareConfig.mountFolder();
        java.nio.file.Path tempDir = Paths.get(basePath, "temp", guid);
        java.nio.file.Path filePath = tempDir.resolve(fileName);

        if (java.nio.file.Files.notExists(filePath)){
            return Response.status(404).entity("File does not exist").build();
        }

        if (java.nio.file.Files.notExists(tempDir)){
            return Response.status(404).entity("Directory does not exist").build();
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
    @Path("/deleteTempFolder/{guid}")
    @Operation(summary = "Delete Temp Folder", description = "Deletes the temp folder, which contains .csv and .zip files from the Query Page and Detailed View")
    @ApiResponse(responseCode = "204", description = "No Content. File has been deleted.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response deleteTempFolder(@PathParam("guid") String guid){

        String basePath = shareConfig.mountFolder();
        File tempDir = new File(basePath, "temp/" + guid);

        try {
            if (tempDir.exists()){
                for(File file : tempDir.listFiles()){
                    file.delete();
                }
                FileUtils.deleteDirectory(tempDir);
                return Response.status(Response.Status.NO_CONTENT).build();
            } else  {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } catch (IOException e) {
            throw new RuntimeException("Error deleting temporary folder" + e.getMessage());
        }
    }

    @GET
    @Path("/listfiles/{assetGuid}")
    @Operation(summary = "Get List of Asset Files in ERDA", description = "Get a list of files in ERDA by Asset Guid.")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("[\"test-institution/test-collection/nt_asset_19/example.jpg\", \"test-institution/test-collection/nt_asset_19/example2.jpg\"]")}))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public List<String> listFilesInErda(@PathParam("assetGuid") String assetGuid, @Context SecurityContext securityContext
    ) {
        return fileService.listFilesInErda(assetGuid);
    }

    @POST
    @Path("/parkedfiles/{path: .+}")
    @Operation(summary = "Upload a file to the Parking spot.", description = "Upload a file to the Parking spot.")
    @Consumes(APPLICATION_OCTET_STREAM)
    @ApiResponse(responseCode = "200", description = "File has been uploaded")
    @ApiResponse(responseCode = "500", content = @Content(mediaType = APPLICATION_OCTET_STREAM))
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public Response postFileToParkedFiles(@PathParam("path") String path, InputStream file){
        String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
        fileService.uploadToParking(file, decodedPath);
        return Response.status(Response.Status.OK).build();
    }

    @DELETE
    @Path("/parkedfiles/{path: .+}")
    @Operation(summary = "Delete a file from the Parking spot.", description = "Delete a file from the Parking spor")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_OCTET_STREAM), description = "File deleted")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = APPLICATION_OCTET_STREAM), description = "Failed to delete the file")
    @ApiResponse(responseCode = "500", content = @Content(mediaType = APPLICATION_OCTET_STREAM))
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public Response deleteFileFromParkedFiles(@PathParam("path") String path){
        String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
        boolean result = this.fileService.deleteAllFilesFromOriginalInParked(decodedPath);
        return Response.status(result ? Response.Status.OK : Response.Status.NOT_FOUND).build();
    }

    @GET
    @Path("/parkedfiles")
    @Operation(summary = "Get a file from the Parking spot.", description = "Get a file from the Parking spot. Checks the correct placement before it checks the Parking spot")
    @Produces(APPLICATION_OCTET_STREAM)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_OCTET_STREAM), description = "Sending the image as a Stream")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = APPLICATION_OCTET_STREAM), description = "Failed to find the file")
    @ApiResponse(responseCode = "500", content = @Content(mediaType = APPLICATION_OCTET_STREAM))
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public Response getFileFromParkedFile(@QueryParam("pathPostFix") String pathPostFix, @QueryParam("institution") String institution, @QueryParam("collection") String collection, @QueryParam("filename") String filename, @QueryParam("type") String type, @QueryParam("scale") Integer scale, @Context SecurityContext securityContext){
        Optional<DasscoFile> dasscoFile = this.fileService.getFilePathForAdapterFile(URLDecoder.decode(institution, StandardCharsets.UTF_8), URLDecoder.decode(collection, StandardCharsets.UTF_8), URLDecoder.decode(filename, StandardCharsets.UTF_8), type, scale);
        String path = pathPostFix + "/" + collection + "/" + type + "/" + filename;
        return dasscoFile.map(value -> {
            FileUploadData fileUploadData = new FileUploadData(value.assetGuid(), institution, collection, value.path(), 0, null);
            //cacheFileService.getFile(institution, collection, guid, path, UserMapper.from(securityContext));
            Optional<FileService.FileResult> getFileResult = cacheFileService.getFile(institution, collection, value.assetGuid(), value.path(), UserMapper.from(securityContext));
            if (getFileResult.isPresent()) {
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
            return Response.status(404).entity("Missing file: %s".formatted(path)).build();
        }).orElseGet(() -> {
            Optional<FileService.FileResult> getFileResult = fileService.readFromParking(path, scale);
            if (getFileResult.isPresent()) {
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
            return Response.status(404).entity("Missing file: %s".formatted(path)).build();
        });
    }

    @GET
    @Path("/parkedfiles/filepath")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = TEXT_PLAIN), description = "Sending the file path")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = TEXT_PLAIN), description = "Failed to find the file")
    @ApiResponse(responseCode = "500", content = @Content(mediaType = APPLICATION_OCTET_STREAM))
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public Response checkIfParkedFileIsThere(@QueryParam("pathPostFix") String pathPostFix, @QueryParam("institution") String institution, @QueryParam("collection") String collection, @QueryParam("filename") String filename, @QueryParam("type") String type, @QueryParam("scale") Integer scale){
        Optional<DasscoFile> dasscoFile = this.fileService.getFilePathForAdapterFile(URLDecoder.decode(institution, StandardCharsets.UTF_8), URLDecoder.decode(collection, StandardCharsets.UTF_8), URLDecoder.decode(filename, StandardCharsets.UTF_8), type, scale);
        String path = pathPostFix + "/" + collection + "/" + type + "/" + filename;
        return dasscoFile.map(value -> {
                    FileUploadData fileUploadData = new FileUploadData(value.assetGuid(), institution, collection, value.path(), 0, null);
                    Optional<FileService.FileResult> getFileResult = fileService.getFile(fileUploadData);
                    if(getFileResult.isPresent()) {
                        return Response.status(200).build();
                    }
                    return Response.status(404).entity("Missing file: %s".formatted(path)).build();
        })
        .orElseGet(() -> {
            Optional<FileService.FileResult> getFileResult = fileService.readFromParking(path, scale);
            if(getFileResult.isPresent()) {
                return Response.status(200).build();
            }
            return Response.status(404).entity("Missing file: %s".formatted(path)).build();
        });
    }
}
