package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

@Path("/large-files/{institutionName}/{collectionName}/{assetGuid}/upload{path: (/.*)?}")
@Tag(
        name = "Large File Uploads",
        description = "TUS 1.0.0 resumable upload endpoint for asset files.",
        externalDocs = @ExternalDocumentation(
                description = "TUS resumable upload protocol 1.0.0",
                url = "https://tus.io/protocols/resumable-upload"
        )
)
@SecurityRequirement(name = "dassco-idp")
public class LargeFiles {
    private static final Logger logger = LoggerFactory.getLogger(LargeFiles.class);
    ShareConfig shareConfig;
    private final FileService fileService;
    private final TusFileUploadService tusFileUploadService;

    @Inject
    public LargeFiles(ShareConfig shareConfig, FileService fileService) {
        this.shareConfig = shareConfig;
        this.fileService = fileService;
        String basePath = "/" + shareConfig.mountFolder() + "/tus";
        String projectRoot = System.getProperty("user.dir");;
        this.tusFileUploadService = new TusFileUploadService()
                .withStoragePath(projectRoot + basePath)
                .withUploadUri("/file_proxy/api/large-files/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/upload");
    }

    @POST
    @Operation(
            summary = "Create TUS upload",
            description = """
                    Creates a TUS upload resource for an asset file. The response `Location` header contains the upload URL that the client must use for subsequent `HEAD`, `PATCH`, and `DELETE` requests.

                    This endpoint implements the TUS creation extension. Metadata values in `Upload-Metadata` must be Base64 encoded as defined by the TUS protocol. The `path` metadata value is used as the destination path within the asset share.
                    """,
            parameters = {
                    @Parameter(name = "Tus-Resumable", in = ParameterIn.HEADER, required = true, description = "TUS protocol version. Must be `1.0.0`.", schema = @Schema(type = "string", allowableValues = {"1.0.0"}), example = "1.0.0"),
                    @Parameter(name = "Upload-Length", in = ParameterIn.HEADER, required = true, description = "Total upload size in bytes.", schema = @Schema(type = "integer", format = "int64", minimum = "0"), example = "104857600"),
                    @Parameter(name = "Upload-Metadata", in = ParameterIn.HEADER, description = "Comma-separated TUS metadata. Values must be Base64 encoded. Supported keys include `filename` and `path`.", schema = @Schema(type = "string"), example = "filename ZXhhbXBsZS50aWY=,path L2ltYWdlcy9leGFtcGxlLnRpZg==")
            },
            responses = {
                    @ApiResponse(responseCode = "201", description = "Upload resource created.", headers = {
                            @Header(name = "Location", description = "URL for the created upload resource.", schema = @Schema(type = "string", format = "uri")),
                            @Header(name = "Tus-Resumable", description = "TUS protocol version used by the server.", schema = @Schema(type = "string", example = "1.0.0"))
                    }),
                    @ApiResponse(responseCode = "400", description = "Bad request, for example missing or invalid TUS headers.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DaSSCoError.class))),
                    @ApiResponse(responseCode = "401", description = "Authentication is required.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DaSSCoError.class))),
                    @ApiResponse(responseCode = "409", description = "The asset share is synchronizing or otherwise conflicts with starting an upload.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DaSSCoError.class))),
                    @ApiResponse(responseCode = "412", description = "Unsupported `Tus-Resumable` version. The response includes `Tus-Version`.", headers = @Header(name = "Tus-Version", description = "Supported TUS protocol versions.", schema = @Schema(type = "string", example = "1.0.0")))
            }
    )
    public void tusPost(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        String uploadLength = request.getHeader("Upload-Length");
        if (uploadLength == null || uploadLength.isBlank()) {
            throw new BadRequestException("Upload-Length header is missing");
        }
        if(this.fileService.enoughStorage(assetGuid, (Integer.parseInt(uploadLength) / 1000000))){
            this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
        }else{
            throw new IllegalArgumentException("Total size of asset files exceeds allocated disk space");
        }
    }

    @PUT
    @Operation(
            summary = "Process TUS PUT request",
            description = "Compatibility endpoint delegated to the TUS upload service. Standard TUS 1.0.0 clients use `POST`, `HEAD`, `PATCH`, `DELETE`, and `OPTIONS`.",
            parameters = @Parameter(name = "Tus-Resumable", in = ParameterIn.HEADER, required = true, description = "TUS protocol version. Must be `1.0.0`.", schema = @Schema(type = "string", allowableValues = {"1.0.0"}), example = "1.0.0"),
            responses = {
                    @ApiResponse(responseCode = "204", description = "TUS request processed successfully.", headers = @Header(name = "Tus-Resumable", description = "TUS protocol version used by the server.", schema = @Schema(type = "string", example = "1.0.0"))),
                    @ApiResponse(responseCode = "400", description = "Bad request.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DaSSCoError.class))),
                    @ApiResponse(responseCode = "401", description = "Authentication is required.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DaSSCoError.class))),
                    @ApiResponse(responseCode = "404", description = "Upload resource not found."),
                    @ApiResponse(responseCode = "409", description = "Upload state conflict, for example an unexpected upload offset.")
            }
    )
    public void tusPut(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
    }

    @DELETE
    @Operation(
            summary = "Terminate TUS upload",
            description = "Terminates an unfinished or completed TUS upload and frees its temporary upload resource. Clients must call this on the upload URL returned by `POST`.",
            parameters = @Parameter(name = "Tus-Resumable", in = ParameterIn.HEADER, required = true, description = "TUS protocol version. Must be `1.0.0`.", schema = @Schema(type = "string", allowableValues = {"1.0.0"}), example = "1.0.0"),
            responses = {
                    @ApiResponse(responseCode = "204", description = "Upload terminated.", headers = @Header(name = "Tus-Resumable", description = "TUS protocol version used by the server.", schema = @Schema(type = "string", example = "1.0.0"))),
                    @ApiResponse(responseCode = "401", description = "Authentication is required.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DaSSCoError.class))),
                    @ApiResponse(responseCode = "404", description = "Upload resource not found."),
                    @ApiResponse(responseCode = "410", description = "Upload resource no longer exists."),
                    @ApiResponse(responseCode = "412", description = "Unsupported `Tus-Resumable` version. The response includes `Tus-Version`.", headers = @Header(name = "Tus-Version", description = "Supported TUS protocol versions.", schema = @Schema(type = "string", example = "1.0.0")))
            }
    )
    public void tusDelete(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
    }

    @PATCH
    @Consumes("*/*")
    @Operation(
            summary = "Upload or resume TUS bytes",
            description = "Uploads bytes to an existing TUS upload resource at the offset provided by `Upload-Offset`. Clients must use the upload URL returned by `POST`.",
            parameters = {
                    @Parameter(name = "Tus-Resumable", in = ParameterIn.HEADER, required = true, description = "TUS protocol version. Must be `1.0.0`.", schema = @Schema(type = "string", allowableValues = {"1.0.0"}), example = "1.0.0"),
                    @Parameter(name = "Upload-Offset", in = ParameterIn.HEADER, required = true, description = "Byte offset where this chunk starts. Must match the server's current upload offset.", schema = @Schema(type = "integer", format = "int64", minimum = "0"), example = "0"),
                    @Parameter(name = "Content-Length", in = ParameterIn.HEADER, required = true, description = "Number of bytes sent in this PATCH request.", schema = @Schema(type = "integer", format = "int64", minimum = "0"), example = "5242880")
            },
            requestBody = @RequestBody(required = true, content = @Content(mediaType = "application/offset+octet-stream", schema = @Schema(type = "string", format = "binary"))),
            responses = {
                    @ApiResponse(responseCode = "204", description = "Chunk accepted.", headers = {
                            @Header(name = "Tus-Resumable", description = "TUS protocol version used by the server.", schema = @Schema(type = "string", example = "1.0.0")),
                            @Header(name = "Upload-Offset", description = "Next byte offset after the accepted chunk.", schema = @Schema(type = "integer", format = "int64", example = "5242880"))
                    }),
                    @ApiResponse(responseCode = "400", description = "Bad request, for example malformed TUS headers.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DaSSCoError.class))),
                    @ApiResponse(responseCode = "401", description = "Authentication is required.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DaSSCoError.class))),
                    @ApiResponse(responseCode = "404", description = "Upload resource not found."),
                    @ApiResponse(responseCode = "409", description = "Upload offset does not match the server's current offset."),
                    @ApiResponse(responseCode = "412", description = "Unsupported `Tus-Resumable` version. The response includes `Tus-Version`.", headers = @Header(name = "Tus-Version", description = "Supported TUS protocol versions.", schema = @Schema(type = "string", example = "1.0.0"))),
                    @ApiResponse(responseCode = "415", description = "PATCH requests must use `Content-Type: application/offset+octet-stream`.")
            }
    )
    public void tusPatch(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
    }

    @HEAD
    @Consumes("*/*")
    @Operation(
            summary = "Get TUS upload offset",
            description = "Returns the current byte offset for a TUS upload resource so a client can resume an interrupted upload. Clients must call this on the upload URL returned by `POST`.",
            parameters = @Parameter(name = "Tus-Resumable", in = ParameterIn.HEADER, required = true, description = "TUS protocol version. Must be `1.0.0`.", schema = @Schema(type = "string", allowableValues = {"1.0.0"}), example = "1.0.0"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Upload status returned.", headers = {
                            @Header(name = "Tus-Resumable", description = "TUS protocol version used by the server.", schema = @Schema(type = "string", example = "1.0.0")),
                            @Header(name = "Upload-Offset", description = "Current byte offset for the upload resource.", schema = @Schema(type = "integer", format = "int64", example = "0")),
                            @Header(name = "Upload-Length", description = "Total upload size in bytes when known.", schema = @Schema(type = "integer", format = "int64", example = "104857600")),
                            @Header(name = "Upload-Metadata", description = "Metadata supplied during upload creation.", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", description = "Set to `no-store` for TUS HEAD responses.", schema = @Schema(type = "string", example = "no-store"))
                    }),
                    @ApiResponse(responseCode = "204", description = "Upload status returned with no response body.", headers = {
                            @Header(name = "Tus-Resumable", description = "TUS protocol version used by the server.", schema = @Schema(type = "string", example = "1.0.0")),
                            @Header(name = "Upload-Offset", description = "Current byte offset for the upload resource.", schema = @Schema(type = "integer", format = "int64", example = "0")),
                            @Header(name = "Upload-Length", description = "Total upload size in bytes when known.", schema = @Schema(type = "integer", format = "int64", example = "104857600"))
                    }),
                    @ApiResponse(responseCode = "401", description = "Authentication is required.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DaSSCoError.class))),
                    @ApiResponse(responseCode = "404", description = "Upload resource not found."),
                    @ApiResponse(responseCode = "410", description = "Upload resource no longer exists."),
                    @ApiResponse(responseCode = "412", description = "Unsupported `Tus-Resumable` version. The response includes `Tus-Version`.", headers = @Header(name = "Tus-Version", description = "Supported TUS protocol versions.", schema = @Schema(type = "string", example = "1.0.0")))
            }
    )
    public void tusHead(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
    }

    @OPTIONS
    @Operation(
            summary = "Get TUS upload capabilities",
            description = "Returns the TUS protocol versions and extensions supported by this upload endpoint. Per the TUS protocol, clients should not send `Tus-Resumable` on `OPTIONS` requests.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "TUS capabilities returned.", headers = {
                            @Header(name = "Tus-Version", description = "Supported TUS protocol versions.", schema = @Schema(type = "string", example = "1.0.0")),
                            @Header(name = "Tus-Extension", description = "Supported TUS extensions.", schema = @Schema(type = "string", example = "creation,termination")),
                            @Header(name = "Tus-Max-Size", description = "Maximum accepted upload size in bytes when configured.", schema = @Schema(type = "integer", format = "int64"))
                    }),
                    @ApiResponse(responseCode = "204", description = "TUS capabilities returned with no response body.", headers = {
                            @Header(name = "Tus-Version", description = "Supported TUS protocol versions.", schema = @Schema(type = "string", example = "1.0.0")),
                            @Header(name = "Tus-Extension", description = "Supported TUS extensions.", schema = @Schema(type = "string", example = "creation,termination")),
                            @Header(name = "Tus-Max-Size", description = "Maximum accepted upload size in bytes when configured.", schema = @Schema(type = "integer", format = "int64"))
                    })
            }
    )
    public void tusOptions(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
    }

    private void handleTusFileUpload(HttpServletRequest request, HttpServletResponse response, SecurityContext securityContext, String institutionName, String collectionName, String assetGuid) {
        User user = UserMapper.from(securityContext);
        try {
            tusFileUploadService.process(request, response);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String uploadURI = request.getRequestURI();

        UploadInfo uploadInfo = null;
        try {
            uploadInfo = this.tusFileUploadService.getUploadInfo(uploadURI);
        }
        catch (IOException | TusException e) {
            logger.error("get upload info", e);
        }
        if ("POST".equals(request.getMethod())) {
            String location = response.getHeader("Location");
            if (location != null) {
                try {
                    fileService.registerActiveLargeUpload(getTusId(location), assetGuid, getUploadMetadata(request, "path").orElse(""));
                } catch (RuntimeException e) {
                    try {
                        this.tusFileUploadService.deleteUpload(location);
                    } catch (IOException | TusException deleteException) {
                        logger.warn("Failed to delete rejected TUS upload", deleteException);
                    }
                    throw e;
                }
            }
        }
        if ("DELETE".equals(request.getMethod())) {
            fileService.unregisterActiveLargeUpload(getTusId(uploadURI));
        }
        if (uploadInfo != null && !uploadInfo.isUploadInProgress()) {
            String tusId = uploadURI.substring(uploadURI.lastIndexOf('/') + 1);
            Long fileSize = uploadInfo.getLength() / 1000000;
            String filename = uploadInfo.getMetadata().get("filename");
            String path = uploadInfo.getMetadata().get("path");
            String contentType = new Tika().detect(path);
            FileUploadData fileUploadData = new FileUploadData(assetGuid, institutionName, collectionName, path, Math.toIntExact(fileSize), contentType);
            //this.fileService.createLargeFileUploadInfo(tusId, assetGuid, fileSize, path);

            try (InputStream is = this.tusFileUploadService.getUploadedBytes(uploadURI)) {
                fileService.largeFileUpload(is, fileUploadData, user.keycloakId);
                fileService.unregisterActiveLargeUpload(tusId);
                this.tusFileUploadService.deleteUpload(uploadURI);
            }
            catch (IOException | TusException e) {
                logger.error("get uploaded bytes", e);
                logger.error("delete upload", e);

            }
        }
    }

    private static String getTusId(String location) {
        String path = location;
        try {
            URI uri = new URI(location);
            if (uri.getPath() != null) {
                path = uri.getPath();
            }
        } catch (URISyntaxException ignored) {
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static Optional<String> getUploadMetadata(HttpServletRequest request, String key) {
        String uploadMetadata = request.getHeader("Upload-Metadata");
        if (uploadMetadata == null || uploadMetadata.isBlank()) {
            return Optional.empty();
        }
        for (String metadataPair : uploadMetadata.split(",")) {
            String[] keyAndValue = metadataPair.trim().split(" ", 2);
            if (keyAndValue.length == 2 && keyAndValue[0].equals(key)) {
                return Optional.of(new String(Base64.getDecoder().decode(keyAndValue[1]), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }
}
