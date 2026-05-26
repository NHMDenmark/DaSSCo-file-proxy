package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
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
    public void tusPut(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
    }

    @DELETE
    public void tusDelete(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
    }

    @PATCH
    @Consumes("*/*")
    public void tusPatch(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
    }

    @HEAD
    @Consumes("*/*")
    public void tusHead(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
    }

    @OPTIONS
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
