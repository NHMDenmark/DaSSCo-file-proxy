package dk.northtech.dasscofileproxy.webapi.v1;

import com.google.gson.Gson;
import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.domain.SecurityRoles;
import dk.northtech.dasscofileproxy.domain.SyncParkingSpaceRequest;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.AssetBundleJobService;
import dk.northtech.dasscofileproxy.service.AssetBundleJobSnapshot;
import dk.northtech.dasscofileproxy.service.AssetBundleJobStatus;
import dk.northtech.dasscofileproxy.service.AssetBundleJobType;
import dk.northtech.dasscofileproxy.service.AssetBundleTooLargeException;
import dk.northtech.dasscofileproxy.service.CacheFileService;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.service.ParkingService;
import dk.northtech.dasscofileproxy.webapi.RangeRequestHandler;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoErrorCode;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoErrorResponse;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static jakarta.ws.rs.core.MediaType.*;

@Path("/assetfiles")
@Tag(name = "Asset Files", description = "Endpoints related to assets' files.")
@SecurityRequirement(name = "dassco-idp")
public class AssetFiles {
    private FileService fileService;
    private CacheFileService cacheFileService;
    private ParkingService parkingService;
    private final AssetBundleJobService assetBundleJobService;
    private final ShareConfig shareConfig;
    private final AssetServiceProperties assetServiceProperties;
    private static final Logger logger = LoggerFactory.getLogger(AssetFiles.class);
    @Inject
    public AssetFiles(FileService fileService, CacheFileService cacheFileService, ShareConfig shareConfig, ParkingService parkingService, AssetServiceProperties assetServiceProperties, AssetBundleJobService assetBundleJobService) {
        this.fileService = fileService;
        this.parkingService = parkingService;
        this.cacheFileService = cacheFileService;
        this.shareConfig = shareConfig;
        this.assetServiceProperties = assetServiceProperties;
        this.assetBundleJobService = assetBundleJobService;
    }

    @Context
    UriInfo uriInfo;

    private Response notFound(String message) {
        return DaSSCoErrorResponse.notFound(message);
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
        return notFound("Asset file not found for institution: %s, collection: %s, assetGuid: %s, path: %s".formatted(institutionName, collectionName, assetGuid, path));
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


    @GET
    @Path("/getTempFile/{guid}/{fileName}")
    @Operation(summary = "Get Temporary File", description = "Gets a file from the Temp Folder (.csv or .zip for downloading assets) used on the query page in the frontend.")
    public Response getTempFile(@PathParam("guid") String guid, @PathParam("fileName") String fileName){
        String basePath = shareConfig.mountFolder();
        java.nio.file.Path tempDir = Paths.get(basePath, "temp", guid);
        java.nio.file.Path filePath = tempDir.resolve(fileName);

        if (java.nio.file.Files.notExists(filePath)){
            return notFound("Temporary file not found for guid: %s, fileName: %s".formatted(guid, fileName));
        }

        if (java.nio.file.Files.notExists(tempDir)){
            return notFound("Temporary directory not found for guid: %s".formatted(guid));
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


    @GET
    @Path("/listfiles/{assetGuid}")
    @Operation(summary = "Get List of Asset Files in ERDA", description = "Get a list of files in ERDA by Asset Guid.")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = String.class)), examples = { @ExampleObject("[\"test-institution/test-collection/nt_asset_19/example.jpg\", \"test-institution/test-collection/nt_asset_19/example2.jpg\"]")}))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public List<String> listFilesInErda(@PathParam("assetGuid") String assetGuid
            , @Context SecurityContext securityContext
            , @QueryParam("includethumbs") @DefaultValue("false") boolean includethumbnails
    ) {
        return fileService.listFilesInErda(assetGuid, includethumbnails);
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
            return notFound("Parked file not found for institution: %s, collection: %s, filename: %s, type: %s, scale: %s, path: %s".formatted(institution, collection, filename, type, scale, path));
        }).orElseGet(() -> {
            Optional<FileService.FileResult> getFileResult = parkingService.readFromParking(path, scale);
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
            return notFound("Parked file not found for institution: %s, collection: %s, filename: %s, type: %s, scale: %s, path: %s".formatted(institution, collection, filename, type, scale, path));
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
                    return notFound("Parked file not found for institution: %s, collection: %s, filename: %s, type: %s, scale: %s, path: %s".formatted(institution, collection, filename, type, scale, path));
        })
        .orElseGet(() -> {
            Optional<FileService.FileResult> getFileResult = parkingService.readFromParking(path, scale);
            if(getFileResult.isPresent()) {
                return Response.status(200).build();
            }
            return notFound("Parked file not found for institution: %s, collection: %s, filename: %s, type: %s, scale: %s, path: %s".formatted(institution, collection, filename, type, scale, path));
        });
    }


    @PUT
    @Path("/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
    @Operation(summary = "Upload File", description = "Uploads a file. Requires institution, collection, asset_guid, crc and file size (in mb).\n\n" +
                                                        "Can be called multiple times to upload multiple files to the same asset. If the files are called the same, the file will be overwritten.")
    @Produces(MediaType.APPLICATION_JSON)
    //@Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @RolesAllowed({SecurityRoles.ADMIN, SecurityRoles.SERVICE, SecurityRoles.DEVELOPER})
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = FileUploadResult.class)))
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response putFile(
            @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @QueryParam("crc") long crc
            , @QueryParam("file_size_mb") int fileSize
            , @QueryParam("has-thumbnail") @DefaultValue("false") boolean hasThumbnail
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
        FileUploadResult upload = fileService.upload(file, crc, fileUploadData, hasThumbnail, user.keycloakId);
        return Response.status(upload.getResponseCode()).entity(upload).build();
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
        var dasscoFiles = fileService.getDasscoFiles(assets, user);
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
    }


    @POST
    @Path("/asset-bundles")
    @Operation(summary = "Download ZIP containing multiple assets", description = """
            Streams a ZIP file for the requested asset GUIDs.
            The request is aborted with 403 Forbidden if the user lacks read access to any requested asset.
            Each asset is placed in its own folder named by asset_guid and contains metadata.csv plus the asset files.
            """)
    @Consumes(APPLICATION_JSON)
    @Produces("application/zip")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/zip"), description = "Returns the ZIP file.")
    @ApiResponse(responseCode = "403", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)), description = "User does not have read access to one or more assets.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response downloadAssetBundles(@Context SecurityContext securityContext, @HeaderParam("Range") String rangeHeader, List<String> assetGuids) {
        if (assetGuids == null || assetGuids.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, "Need to pass a list of assets"))
                    .build();
        }

        User user = UserMapper.from(securityContext);
        for (String assetGuid : assetGuids) {
            if (assetGuid == null || assetGuid.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, "Asset GUID cannot be blank"))
                        .build();
            }
        }

        Map<String, String> metadataCsvByAsset;
        try {
            HttpResponse<String> assetsResponse = fetchAssets(assetGuids, user);
            if (assetsResponse.statusCode() == 403) {
                return Response.status(Response.Status.FORBIDDEN).entity(assetsResponse.body()).build();
            }
            if (assetsResponse.statusCode() < 200 || assetsResponse.statusCode() >= 300) {
                return Response.status(assetsResponse.statusCode()).entity(assetsResponse.body()).build();
            }
            metadataCsvByAsset = createMetadataCsvByAsset(assetsResponse.body(), assetGuids);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new DaSSCoError("1.0", DaSSCoErrorCode.INTERNAL_ERROR, "Interrupted while checking asset read access"))
                    .build();
        } catch (Exception e) {
            logger.error("Failed to check read access/create CSV for assets", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new DaSSCoError("1.0", DaSSCoErrorCode.INTERNAL_ERROR, "Failed to check asset read access"))
                    .build();
        }

        Map<String, List<DasscoFile>> filesByAsset = fileService.getDasscoFiles(assetGuids, user)
                .stream()
                .collect(Collectors.groupingBy(DasscoFile::assetGuid));

        File tempZipFile;
        try {
            tempZipFile = File.createTempFile("asset-bundle-", ".zip");
            tempZipFile.deleteOnExit();
            createAssetBundleZip(tempZipFile, assetGuids, metadataCsvByAsset, filesByAsset, user);
        } catch (IOException e) {
            logger.error("Failed to create asset bundle ZIP", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new DaSSCoError("1.0", DaSSCoErrorCode.INTERNAL_ERROR, "Failed to create asset bundle ZIP"))
                    .build();
        }

        return RangeRequestHandler.buildFileResponse(
                new RangeRequestHandler.FileResponseConfig(tempZipFile, "asset-bundle.zip", "application/zip")
                        .withRangeHeader(rangeHeader)
                        .withoutContentDisposition()
                        .cleanupAfterAnyRange()
                        .onComplete(() -> {
                            if (tempZipFile.exists() && !tempZipFile.delete()) {
                                logger.warn("Failed to delete temporary ZIP {}", tempZipFile.getAbsolutePath());
                            }
                        }));
    }

    @POST
    @Path("/asset-bundles/jobs")
    @Operation(summary = "Prepare ZIP containing multiple assets", description = """
            Starts an asynchronous asset bundle preparation job and returns immediately.
            Poll the returned Location until the status is READY, then download from the download endpoint.
            """)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @ApiResponse(responseCode = "202", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = AssetBundleJobSnapshot.class)), description = "Asset bundle preparation started.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response startAssetBundleJob(@Context SecurityContext securityContext, List<String> assetGuids) {
        Response validationError = validateAssetBundleRequest(assetGuids);
        if (validationError != null) {
            return validationError;
        }

        AssetBundleJobSnapshot job;
        try {
            job = assetBundleJobService.start(assetGuids, UserMapper.from(securityContext));
        } catch (AssetBundleTooLargeException e) {
            return assetBundleTooLarge(e);
        }
        URI statusUri = uriInfo.getAbsolutePathBuilder().path(job.jobId()).build();

        return Response.accepted(job)
                .location(statusUri)
                .build();
    }

    @POST
    @Path("/asset-bundles/extern/jobs")
    @Operation(summary = "Prepare ZIP containing multiple externally available assets", description = """
            Starts an asynchronous external asset bundle preparation job and returns immediately.
            This endpoint supports anonymous access and uses external asset metadata/access rules.
            """)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @ApiResponse(responseCode = "202", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = AssetBundleJobSnapshot.class)), description = "External asset bundle preparation started.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response startExternalAssetBundleJob(@Context SecurityContext securityContext, List<String> assetGuids) {
        Response validationError = validateAssetBundleRequest(assetGuids);
        if (validationError != null) {
            return validationError;
        }

        AssetBundleJobSnapshot job;
        try {
            job = assetBundleJobService.startExternal(assetGuids, new User("anonymous"));
        } catch (AssetBundleTooLargeException e) {
            return assetBundleTooLarge(e);
        }
        URI statusUri = uriInfo.getAbsolutePathBuilder().path(job.jobId()).build();

        return Response.accepted(job)
                .location(statusUri)
                .build();
    }

    @GET
    @Path("/asset-bundles/jobs/{jobId}")
    @Operation(summary = "Get asset bundle preparation status", description = "Returns PREPARING, READY, or FAILED for an asynchronous asset bundle job.")
    @Produces(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = AssetBundleJobSnapshot.class)), description = "Returns the job status.")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)), description = "Asset bundle job not found.")
    public Response getAssetBundleJob(@PathParam("jobId") String jobId, @Context SecurityContext securityContext) {
        return getAssetBundleJob(jobId, AssetBundleJobType.INTERNAL, UserMapper.from(securityContext));
    }

    private Response getAssetBundleJob(String jobId, AssetBundleJobType jobType, User user) {
        return assetBundleJobService.get(jobId, jobType, user)
                .map(job -> Response.ok(job).build())
                .orElseGet(() -> notFound("Asset bundle job not found: %s".formatted(jobId)));
    }

    @DELETE
    @Path("/asset-bundles/jobs/{jobId}")
    @Operation(summary = "Cancel asset bundle preparation", description = "Cancels an asynchronous asset bundle job and removes any prepared ZIP file.")
    @ApiResponse(responseCode = "204", description = "Asset bundle job cancelled.")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)), description = "Asset bundle job not found.")
    public Response cancelAssetBundleJob(@PathParam("jobId") String jobId, @Context SecurityContext securityContext) {
        return cancelAssetBundleJob(jobId, AssetBundleJobType.INTERNAL, UserMapper.from(securityContext));
    }

    private Response cancelAssetBundleJob(String jobId, AssetBundleJobType jobType, User user) {
        if (assetBundleJobService.cancel(jobId, jobType, user)) {
            return Response.noContent().build();
        }
        return notFound("Asset bundle job not found: %s".formatted(jobId));
    }

    @GET
    @Path("/asset-bundles/extern/jobs/{jobId}")
    @Operation(summary = "Get external asset bundle preparation status", description = "Returns PREPARING, READY, or FAILED for an asynchronous external asset bundle job.")
    @Produces(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = AssetBundleJobSnapshot.class)), description = "Returns the job status.")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)), description = "Asset bundle job not found.")
    public Response getExternalAssetBundleJob(@PathParam("jobId") String jobId) {
        return getAssetBundleJob(jobId, AssetBundleJobType.EXTERNAL, new User("anonymous"));
    }

    @DELETE
    @Path("/asset-bundles/extern/jobs/{jobId}")
    @Operation(summary = "Cancel external asset bundle preparation", description = "Cancels an asynchronous external asset bundle job and removes any prepared ZIP file.")
    @ApiResponse(responseCode = "204", description = "External asset bundle job cancelled.")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)), description = "Asset bundle job not found.")
    public Response cancelExternalAssetBundleJob(@PathParam("jobId") String jobId) {
        return cancelAssetBundleJob(jobId, AssetBundleJobType.EXTERNAL, new User("anonymous"));
    }

    @GET
    @Path("/asset-bundles/jobs/{jobId}/download")
    @Operation(summary = "Download prepared asset bundle", description = "Downloads a prepared asynchronous asset bundle ZIP. Returns 409 until the job is READY.")
    @Produces("application/zip")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/zip"), description = "Returns the ZIP file.")
    @ApiResponse(responseCode = "409", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)), description = "Asset bundle is not ready yet.")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)), description = "Asset bundle job not found.")
    public Response downloadAssetBundleJob(@PathParam("jobId") String jobId, @HeaderParam("Range") String rangeHeader, @Context SecurityContext securityContext) {
        return downloadAssetBundleJob(jobId, rangeHeader, AssetBundleJobType.INTERNAL, UserMapper.from(securityContext));
    }

    private Response downloadAssetBundleJob(String jobId, String rangeHeader, AssetBundleJobType jobType, User user) {
        Optional<AssetBundleJobSnapshot> job = assetBundleJobService.get(jobId, jobType, user);
        if (job.isEmpty()) {
            return notFound("Asset bundle job not found: %s".formatted(jobId));
        }
        if (job.get().status() != AssetBundleJobStatus.READY) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, "Asset bundle is not ready"))
                    .build();
        }

        Optional<File> bundleFile = assetBundleJobService.getBundleFileForDownload(jobId, jobType, user);
        if (bundleFile.isEmpty()) {
            return notFound("Asset bundle file not found for job: %s".formatted(jobId));
        }

        return RangeRequestHandler.buildFileResponse(
                new RangeRequestHandler.FileResponseConfig(bundleFile.get(), "asset-bundle.zip", "application/zip")
                        .withRangeHeader(rangeHeader)
                        .withoutContentDisposition()
                        .onStart(() -> assetBundleJobService.startDownload(jobId, jobType, user))
                        .onFinished(() -> assetBundleJobService.finishDownload(jobId))
                        .onComplete(() -> assetBundleJobService.complete(jobId)));
    }

    @GET
    @Path("/asset-bundles/extern/jobs/{jobId}/download")
    @Operation(summary = "Download prepared external asset bundle", description = "Downloads a prepared asynchronous external asset bundle ZIP. Returns 409 until the job is READY.")
    @Produces("application/zip")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/zip"), description = "Returns the ZIP file.")
    @ApiResponse(responseCode = "409", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)), description = "Asset bundle is not ready yet.")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)), description = "Asset bundle job not found.")
    public Response downloadExternalAssetBundleJob(@PathParam("jobId") String jobId, @HeaderParam("Range") String rangeHeader) {
        return downloadAssetBundleJob(jobId, rangeHeader, AssetBundleJobType.EXTERNAL, new User("anonymous"));
    }

    private Response validateAssetBundleRequest(List<String> assetGuids) {
        if (assetGuids == null || assetGuids.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, "Need to pass a list of assets"))
                    .build();
        }

        for (String assetGuid : assetGuids) {
            if (assetGuid == null || assetGuid.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new DaSSCoError("1.0", DaSSCoErrorCode.BAD_REQUEST, "Asset GUID cannot be blank"))
                        .build();
            }
        }
        return null;
    }

    private Response assetBundleTooLarge(AssetBundleTooLargeException e) {
        return Response.status(413)
                .type(APPLICATION_JSON)
                .entity(new AssetBundleTooLargeResponse(e.getMessage(), e.totalSizeBytes(), e.maxSizeBytes(), e.assetCount()))
                .build();
    }

    private record AssetBundleTooLargeResponse(String message, long totalSizeBytes, long maxSizeBytes, int assetCount) {
    }

    private void createAssetBundleZip(File zipFile, List<String> assetGuids, Map<String, String> metadataCsvByAsset, Map<String, List<DasscoFile>> filesByAsset, User user) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            Set<String> zipEntries = new HashSet<>();
            for (String assetGuid : assetGuids) {
                String assetFolder = safeZipName(assetGuid) + "/";
                addDirectoryEntry(zip, zipEntries, assetFolder);
                addMetadataCsvEntry(zip, zipEntries, assetFolder, metadataCsvByAsset.get(assetGuid));

                for (DasscoFile dasscoFile : filesByAsset.getOrDefault(assetGuid, List.of())) {
                    AssetPath assetPath = parseAssetPath(dasscoFile.path());
                    if (assetPath == null) {
                        logger.warn("Skipping file with unexpected asset path {}", dasscoFile.path());
                        continue;
                    }

                    Optional<FileService.FileResult> file = cacheFileService.tryGetFile(
                            assetPath.institution(), assetPath.collection(), assetPath.assetGuid(), assetPath.filePath(), user);
                    if (file.isPresent()) {
                        String entryName = assetFolder + safeZipPath(assetPath.filePath());
                        addFileEntry(zip, zipEntries, entryName, file.get());
                    }
                }
            }
        }
    }

    private HttpResponse<String> fetchAssets(List<String> assetGuids, User user) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(assetServiceProperties.rootUrl() + "/api/v1/assets/list"))
                .header("Content-Type", APPLICATION_JSON)
                .header("Accept", APPLICATION_JSON)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(assetGuids)));
        if (user.token != null) {
            requestBuilder.header("Authorization", "Bearer " + user.token);
        }

        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void addMetadataCsvEntry(ZipOutputStream zip, Set<String> zipEntries, String assetFolder, String csvString) throws IOException {
        if (csvString == null) {
            return;
        }
        addFileEntry(zip, zipEntries, assetFolder + "metadata.csv", csvString);
    }

    private Map<String, String> createMetadataCsvByAsset(String assetsJson, List<String> assetGuids) {
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<Map<String, Object>>>() {}.getType();
        List<Map<String, Object>> assets = new Gson().fromJson(assetsJson, type);
        Map<String, String> metadataCsvByAsset = new java.util.LinkedHashMap<>();
        if (assets == null || assets.isEmpty()) {
            return metadataCsvByAsset;
        }

        java.util.LinkedHashSet<String> headers = new java.util.LinkedHashSet<>();
        assets.forEach(asset -> headers.addAll(asset.keySet()));
        String headerLine = headers.stream().map(this::csvEscape).collect(Collectors.joining(","));

        for (Map<String, Object> asset : assets) {
            Object assetGuidValue = asset.containsKey("asset_guid") ? asset.get("asset_guid") : asset.get("assetGuid");
            if (assetGuidValue == null) {
                logger.warn("Skipping metadata CSV for asset without asset_guid: {}", asset);
                continue;
            }
            String assetGuid = String.valueOf(assetGuidValue);
            String row = headers.stream()
                    .map(header -> csvEscape(formatCsvValue(asset.get(header))))
                    .collect(Collectors.joining(","));
            metadataCsvByAsset.put(assetGuid, "sep=,\r\n" + headerLine + "\r\n" + row + "\r\n");
        }

        for (String assetGuid : assetGuids) {
            metadataCsvByAsset.putIfAbsent(assetGuid, "sep=,\r\n" + headerLine + "\r\n");
        }
        return metadataCsvByAsset;
    }

    private String formatCsvValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return new Gson().toJson(value);
        }
        return String.valueOf(value);
    }

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean mustQuote = value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n");
        String escaped = value.replace("\"", "\"\"");
        return mustQuote ? "\"" + escaped + "\"" : escaped;
    }

    private void addDirectoryEntry(ZipOutputStream zip, Set<String> zipEntries, String entryName) throws IOException {
        if (zipEntries.add(entryName)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.closeEntry();
        }
    }

    private void addFileEntry(ZipOutputStream zip, Set<String> zipEntries, String entryName, FileService.FileResult file) throws IOException {
        if (!zipEntries.add(entryName)) {
            logger.warn("Skipping duplicate ZIP entry {}", entryName);
            return;
        }
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream inputStream = file.is()) {
            inputStream.transferTo(zip);
        }
        zip.closeEntry();
    }

    private void addFileEntry(ZipOutputStream zip, Set<String> zipEntries, String entryName, String content) throws IOException {
        if (!zipEntries.add(entryName)) {
            logger.warn("Skipping duplicate ZIP entry {}", entryName);
            return;
        }
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private AssetPath parseAssetPath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String[] parts = normalized.split("/", 4);
        if (parts.length < 4) {
            return null;
        }
        return new AssetPath(parts[0], parts[1], parts[2], parts[3]);
    }

    private String safeZipPath(String path) {
        StringBuilder safePath = new StringBuilder();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                continue;
            }
            if (!safePath.isEmpty()) {
                safePath.append('/');
            }
            safePath.append(part);
        }
        return safePath.toString();
    }

    private String safeZipName(String name) {
        return safeZipPath(name).replace("/", "_");
    }

    private record AssetPath(String institution, String collection, String assetGuid, String filePath) {}


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


    @POST
    @Path("/parkedfiles/{path: .+}")
    @Operation(summary = "Upload a file to the Parking spot.", description = "Upload a file to the Parking spot.")
    @Consumes(APPLICATION_OCTET_STREAM)
    @ApiResponse(responseCode = "200", description = "File has been uploaded")
    @ApiResponse(responseCode = "500", content = @Content(mediaType = APPLICATION_OCTET_STREAM))
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public Response postFileToParkedFiles(@PathParam("path") String path, InputStream file){
        String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
        logger.info("Received file to parked: {}", decodedPath);
        parkingService.uploadToParking(file, decodedPath);
        return Response.status(Response.Status.OK).build();
    }


    @POST
    @Path("/syncparkedfiles/")
    @Operation(summary = "Upload a file to the Parking spot.", description = "Upload a file to the Parking spot.")
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "File has been uploaded")
    @ApiResponse(responseCode = "500", content = @Content(mediaType = APPLICATION_OCTET_STREAM))
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public Response syncParkedFiles(SyncParkingSpaceRequest syncParkingSpaceRequest, @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        if(parkingService.syncParkedFiles(syncParkingSpaceRequest, user)){
            return Response.status(Response.Status.ACCEPTED).build();
        } else {
            return Response.status(Response.Status.OK).build();
        }

    }


    @DELETE
    @Path("/{institutionName}/{collectionName}/{assetGuid}/{path: .+}")
    @Operation(summary = "Delete Asset File by path", description = "Delete resource at the given path. If the resource is a directory, it will be deleted along its content. If the resource is the base directory for an asset the directory will not be deleted, only the content.")
    @ApiResponse(responseCode = "204", description = "No Content. File has been deleted.")
    @RolesAllowed({SecurityRoles.ADMIN})
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response deletefile(
            @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        final String path
                = uriInfo.getPathParameters().getFirst("path");
        boolean deleted = fileService.deleteFile(new FileUploadData(assetGuid, institutionName, collectionName, path, 0,null), user.keycloakId);
        return deleted ? Response.status(Response.Status.NO_CONTENT).build() : notFound("Asset file not found for institution: %s, collection: %s, assetGuid: %s, path: %s".formatted(institutionName, collectionName, assetGuid, path));
    }

    //Delete all files under an asset
    @DELETE
    @Path("/{institutionName}/{collectionName}/{assetGuid}/")
    @Operation(summary = "Delete Asset Files", description = "Deletes all files for an asset based on institution, collection and asset_guid")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.ADMIN})
    @ApiResponse(responseCode = "204", description = "No Content. File has been deleted.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    public Response deleteAsset(
            @PathParam("institutionName") String institutionName
            , @PathParam("collectionName") String collectionName
            , @PathParam("assetGuid") String assetGuid
            , @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        boolean deleted = fileService.deleteFile(new FileUploadData(assetGuid, institutionName, collectionName, null, 0,null), user.keycloakId);
        return deleted ? Response.status(Response.Status.NO_CONTENT).build() : notFound("Asset files not found for institution: %s, collection: %s, assetGuid: %s".formatted(institutionName, collectionName, assetGuid));
    }


    @DELETE
    @Path("/deleteLocalFiles/{institution}/{collection}/{assetGuid}/{file}")
    @RolesAllowed({SecurityRoles.ADMIN})
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


    @DELETE
    @Path("/parkedfiles/{path: .+}")
    @Operation(summary = "Delete a file from the Parking spot.", description = "Delete a file from the Parking spor")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = APPLICATION_OCTET_STREAM), description = "File deleted")
    @ApiResponse(responseCode = "404", content = @Content(mediaType = APPLICATION_OCTET_STREAM), description = "Failed to delete the file")
    @ApiResponse(responseCode = "500", content = @Content(mediaType = APPLICATION_OCTET_STREAM))
    @RolesAllowed({SecurityRoles.ADMIN, SecurityRoles.SERVICE})
    public Response deleteFileFromParkedFiles(@PathParam("path") String path){
        String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
        boolean result = this.parkingService.deleteAllFilesFromOriginalInParked(decodedPath);
        return result ? Response.status(Response.Status.OK).build() : notFound("Parked file not found for path: %s".formatted(decodedPath));
    }

}
