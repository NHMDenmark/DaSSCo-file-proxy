package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoUnauthorizedException;
import dk.northtech.dasscofileproxy.service.CacheFileService;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Path("/files")
@Tag(name = "Asset Files", description = "Endpoints related to assets' files.")
@SecurityRequirement(name = "dassco-idp")
public class Files {
    private static final Logger logger = LoggerFactory.getLogger(Files.class);
    private final CacheFileService cacheFileService;
    private FileService fileService;
    private final AssetServiceProperties assetServiceProperties;
    @Context
    UriInfo uriInfo;

    @Inject
    public Files(CacheFileService cacheFileService, FileService fileService,
                 AssetServiceProperties assetServiceProperties) {
        this.cacheFileService = cacheFileService;
        this.fileService = fileService;
        this.assetServiceProperties = assetServiceProperties;
    }

    @GET
    @Operation(summary = "Get File From ERDA", description = """
            Gets a file from ERDA on the give path. If 'no-cache' is true, then the file won't be saved in the cache and will be streamed instead. 'no-cache' is false by default.
            """)
    @Path("/assets/{institution}/{collection}/{assetGuid}/{path: .+}")
    public Response getFile(
            @PathParam("institution") String institution, @PathParam("collection") String collection,
            @PathParam("assetGuid") String guid, @Context SecurityContext securityContext,
            @QueryParam("no-cache") @DefaultValue("false") boolean noCache) {
        final String path = uriInfo.getPathParameters().getFirst("path");
        logger.info("Getting file from collection, {}, on path: {}", collection, path);
        User user = securityContext.getUserPrincipal() == null ? new User("anonymous")
                : UserMapper.from(securityContext);
        try {
            if (!noCache) {
                Optional<FileService.FileResult> file = cacheFileService.getFile(institution, collection, guid, path,
                        user);
                logger.info("got file");

                if (file.isPresent()) {
                    FileService.FileResult fileResult = file.get();
                    StreamingOutput streamingOutput = output -> {
                        fileResult.is().transferTo(output);
                        output.flush();
                    };

                    return Response.status(200)
                            .header("Content-Disposition", "attachment; filename=" + fileResult.filename())
                            .header("Content-Type", new Tika().detect(fileResult.filename())).entity(streamingOutput)
                            .build();
                } else {
                    return Response.status(404).build();
                }
            } else {
                return cacheFileService.streamFile(institution, collection, guid, path, user, false);
            }
        } catch (DasscoUnauthorizedException e) {
            String redirectUrl = assetServiceProperties.rootUrl() + "/detailed-view/" + guid;
            return Response.status(Response.Status.TEMPORARY_REDIRECT).location(URI.create(redirectUrl)).build();
        }
    }

    @GET
    @Operation(summary = "Download Large File From ERDA (Resumable, without login)", description = """
            Downloads a file from ERDA with support for HTTP Range requests, enabling browser pause/resume functionality.
            The file is first cached locally, then served with proper range support for large file downloads.
            Supports the standard HTTP Range header for partial content requests.
            This endpoint can be called without a token and is designed to be used with HTML anchor tags with download attribute.
            """)
    @Path("/assets/download/{institution}/{collection}/{assetGuid}")
    public Response downloadLargeFile(
            @PathParam("institution") String institution,
            @PathParam("collection") String collection,
            @PathParam("assetGuid") String assetGuid,
            @QueryParam("ticket") String ticket,
            @HeaderParam("Range") String rangeHeader) {
        final String path = this.cacheFileService.useTicket(ticket);
        logger.info("Downloading large file from collection {}, path: {}, range: {}", collection, path, rangeHeader);

        try {
            Optional<CacheFileService.CachedFileInfo> cachedFileInfo = cacheFileService.getCachedFileWithoutUser(institution,
                    collection, assetGuid, path);

            if (cachedFileInfo.isEmpty()) {
                return Response.status(404).build();
            }

            CacheFileService.CachedFileInfo fileInfo = cachedFileInfo.get();
            File file = fileInfo.file();
            long fileLength = file.length();
            String contentType = new Tika().detect(fileInfo.filename());
            String contentDisposition = "attachment; filename=\"" + fileInfo.filename() + "\"";

            // If no Range header, return the entire file
            if (rangeHeader == null || rangeHeader.isEmpty()) {
                StreamingOutput streamingOutput = output -> {
                    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = raf.read(buffer)) != -1) {
                            output.write(buffer, 0, bytesRead);
                        }
                        output.flush();
                    }
                };

                return Response.ok(streamingOutput)
                        .header("Content-Disposition", contentDisposition)
                        .header("Content-Type", contentType)
                        .header("Content-Length", fileLength)
                        .header("Accept-Ranges", "bytes")
                        .build();
            }

            // Parse Range header (format: "bytes=start-end" or "bytes=start-")
            if (!rangeHeader.startsWith("bytes=")) {
                return Response.status(416) // Range Not Satisfiable
                        .header("Content-Range", "bytes */" + fileLength)
                        .build();
            }

            String rangeValue = rangeHeader.substring(6); // Remove "bytes="
            String[] rangeParts = rangeValue.split("-");

            long start;
            long end;

            try {
                start = Long.parseLong(rangeParts[0]);
                if (rangeParts.length > 1 && !rangeParts[1].isEmpty()) {
                    end = Long.parseLong(rangeParts[1]);
                } else {
                    end = fileLength - 1;
                }
            } catch (NumberFormatException e) {
                return Response.status(416)
                        .header("Content-Range", "bytes */" + fileLength)
                        .build();
            }

            // Validate range
            if (start < 0 || start >= fileLength || end < start || end >= fileLength) {
                return Response.status(416)
                        .header("Content-Range", "bytes */" + fileLength)
                        .build();
            }

            final long rangeStart = start;
            final long rangeEnd = end;
            final long contentLength = rangeEnd - rangeStart + 1;

            StreamingOutput streamingOutput = output -> {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(rangeStart);
                    byte[] buffer = new byte[8192];
                    long remaining = contentLength;
                    int bytesRead;

                    while (remaining > 0
                            && (bytesRead = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                        output.write(buffer, 0, bytesRead);
                        remaining -= bytesRead;
                    }
                    output.flush();
                } finally {
                    if (rangeEnd == fileLength - 1) {
                        this.cacheFileService.invalidateTicket(ticket);
                    }
                }
            };

            return Response.status(206)
                    .entity(streamingOutput)
                    .header("Content-Disposition", contentDisposition)
                    .header("Content-Type", contentType)
                    .header("Content-Length", contentLength)
                    .header("Content-Range", "bytes " + rangeStart + "-" + rangeEnd + "/" + fileLength)
                    .header("Accept-Ranges", "bytes")
                    .build();

        } catch (DasscoUnauthorizedException e) {
            String redirectUrl = assetServiceProperties.rootUrl() + "/detailed-view/" + assetGuid;
            return Response.status(Response.Status.TEMPORARY_REDIRECT).location(URI.create(redirectUrl)).build();
        }
    }

    @GET
    @Operation(summary = "Get Asset Thumbnail (without login)", description = """
            Gets the thumbnail for the given asset for external users. This can be called without a token.
            """)
    @Path("/assets/{institutionName}/{collectionName}/{assetGuid}/thumbnail")
    public Response getFileFromGuid(@PathParam("institutionName") String institutionName,
                                    @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid,
                                    @Context SecurityContext securityContext, @QueryParam("no-cache") @DefaultValue("false") boolean noCache) {
        User user = securityContext.getUserPrincipal() == null ? new User("anonymous")
                : UserMapper.from(securityContext);
        Optional<DasscoFile> dasscoFile = this.fileService.getDasscoFileThumbnailForGuid(assetGuid);
        if (dasscoFile.isPresent()) {
            String path = dasscoFile.get().path();
            if (!(path.toLowerCase().endsWith(".jpeg") || path.toLowerCase().endsWith(".jpg")
                    || path.toLowerCase().endsWith(".png"))) {
                return Response.status(404).build();
            }
            try {
                String fileName = List.of(path.split("/")).getLast();

                if (!noCache) {
                    Optional<FileService.FileResult> file = cacheFileService.getFile(institutionName, collectionName,
                            assetGuid, fileName, user);
                    if (file.isPresent()) {
                        FileService.FileResult fileResult = file.get();
                        StreamingOutput streamingOutput = output -> {
                            fileResult.is().transferTo(output);
                            output.flush();
                        };

                        return Response.status(200)
                                .header("Content-Disposition", "inline; attachment; filename=" + fileResult.filename())
                                .header("Content-Type", new Tika().detect(fileResult.filename()))
                                .entity(streamingOutput).build();
                    } else {
                        return Response.status(404).build();
                    }
                } else {
                    return cacheFileService.streamFile(institutionName, collectionName, assetGuid, fileName, user,
                            true);
                }
            } catch (Exception e) {
                logger.error(e.toString());
            }
        }

        return Response.status(404).build();
    }

    @GET
    @Path("/assets/extern/{institutionName}/{collectionName}/{assetGuid}")
    @Operation(summary = "Get Latest File From ERDA as External (without login)", description = """
            Gets the latest file uploaded to ERDA for the given asset for external users. This can be called without a token.
            """)
    public Response getExternFileFromGuid(@PathParam("institutionName") String institutionName,
                                          @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid,
                                          @Context SecurityContext securityContext) {
        User user = securityContext.getUserPrincipal() == null ? new User("anonymous")
                : UserMapper.from(securityContext);
        Optional<DasscoFile> dasscoFile = this.fileService.getDasscoFileForGuid(assetGuid);
        if (dasscoFile.isPresent()) {
            String path = dasscoFile.get().path();
            try {
                String fileName = List.of(path.split("/")).getLast();
                return cacheFileService.streamFile(institutionName, collectionName, assetGuid, fileName, user, false);
            } catch (Exception e) {
                logger.error(e.toString());
            }
        }

        return Response.status(404).build();
    }

    @GET
    @Path("assets/extern/{institution}/{collection}/{assetGuid}/zip")
    @Produces("application/zip")
    @Operation(summary = "Download ZIP containing all existing files, thumbnail, and metadata CSV", description = """
                Creates and streams a ZIP for the given asset.
                It first queries listAvailableFiles() to determine which files actually exist,
                then safely includes the main files, thumbnail (if present), and metadata CSV.
                Missing or inaccessible files are skipped.
            """)
    public Response getAssetBundleZip(
            @PathParam("institution") String institution,
            @PathParam("collection") String collection,
            @PathParam("assetGuid") String assetGuid,
            @Context SecurityContext securityContext) {

        User user = (securityContext.getUserPrincipal() == null)
                ? new User("anonymous")
                : UserMapper.from(securityContext);

        List<String> availableFiles = fileService.listAvailableFiles(
                new FileUploadData(assetGuid, institution, collection, null, 0, null));

        if (availableFiles == null || availableFiles.isEmpty()) {
            logger.warn("No available files found for asset {}", assetGuid);
        }

        Optional<DasscoFile> thumbOpt = fileService.getDasscoFileThumbnailForGuid(assetGuid);

        final List<String> existingPaths = availableFiles;
        final Optional<DasscoFile> thumbnail = thumbOpt;
        final String guidForStream = assetGuid;

        StreamingOutput stream = output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output)) {

                for (String path : existingPaths) {
                    String filePath = path;
                    if (filePath.startsWith("/"))
                        filePath = filePath.substring(1);

                    String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
                    try {
                        Optional<FileService.FileResult> fileResOpt = cacheFileService.tryGetFile(institution,
                                collection,
                                assetGuid, fileName, user);
                        if (fileResOpt.isPresent()) {
                            FileService.FileResult fileRes = fileResOpt.get();
                            ZipEntry entry = new ZipEntry(fileRes.filename());
                            zip.putNextEntry(entry);
                            try (InputStream is = fileRes.is()) {
                                is.transferTo(zip);
                            }
                            zip.closeEntry();
                            logger.debug("Added {} to ZIP for {}", fileRes.filename(), assetGuid);
                        }
                    } catch (Exception e) {
                        logger.warn("Skipping {} for {}: {}", filePath, assetGuid, e.getMessage());
                    }
                }

                try {
                    if (thumbnail.isPresent()) {
                        DasscoFile thumb = thumbnail.get();
                        String thumbPath = thumb.path();
                        if (thumbPath != null && (thumbPath.toLowerCase().endsWith(".jpg")
                                || thumbPath.toLowerCase().endsWith(".jpeg")
                                || thumbPath.toLowerCase().endsWith(".png"))) {
                            String fileName = thumbPath.substring(thumbPath.lastIndexOf('/') + 1);
                            Optional<FileService.FileResult> thumbResOpt = cacheFileService.tryGetFile(institution,
                                    collection,
                                    assetGuid, fileName, user);
                            if (thumbResOpt.isPresent()) {
                                FileService.FileResult thumbRes = thumbResOpt.get();
                                ZipEntry thumbEntry = new ZipEntry("thumbnail_" + thumbRes.filename());
                                zip.putNextEntry(thumbEntry);
                                try (InputStream is = thumbRes.is()) {
                                    is.transferTo(zip);
                                }
                                zip.closeEntry();
                                logger.debug("Added thumbnail {} to ZIP for {}", thumbRes.filename(), assetGuid);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Skipping thumbnail for {}: {}", assetGuid, e.getMessage());
                }
                try {
                    String csvUrl = assetServiceProperties.rootUrl()
                            + "/api/extern/metadata/" + guidForStream + "/csv";

                    logger.debug("Fetching metadata CSV from {}", csvUrl);
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(csvUrl))
                            .header("Accept", "text/csv")
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build();

                    HttpClient client = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();

                    HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());

                    int code = resp.statusCode();
                    if (code >= 200 && code < 300 && resp.body() != null) {
                        ZipEntry csvEntry = new ZipEntry("metadata_" + guidForStream + ".csv");
                        zip.putNextEntry(csvEntry);
                        try (InputStream csvStream = resp.body()) {
                            csvStream.transferTo(zip);
                        }
                        zip.closeEntry();
                        logger.debug("Added metadata CSV for asset {}", guidForStream);
                    } else {
                        logger.warn("Metadata CSV not available for {} (HTTP {})", guidForStream, code);
                    }
                } catch (Exception e) {
                    logger.warn("Error adding metadata CSV for {}: {}", guidForStream, e.getMessage());
                }

                zip.finish();
                zip.flush();
            }
        };

        return Response.ok(stream, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + assetGuid + "_bundle.zip\"")
                .build();
    }

    @GET
    @Operation(summary = "Gets Ticket For Large File Download",
            description = """
                    Gets a ticket used to download large files, the endpoint validates user access before granting the ticket.
                    Ticket lasts for 1 day or until download is finished.
                    """)
    @Path("/assets/{assetGuid}/ticket")
    public String GetFileTicket(@PathParam("assetGuid") String assetGuid, @Context SecurityContext securityContext) {
        logger.info("Getting ticket for asset: {}", assetGuid);
        User user = securityContext.getUserPrincipal() == null ? new User("anonymous")
                : UserMapper.from(securityContext);
        return this.cacheFileService.createTicket(user, assetGuid);
    }
}
