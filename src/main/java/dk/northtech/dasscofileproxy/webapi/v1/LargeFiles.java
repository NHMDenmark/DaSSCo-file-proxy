package dk.northtech.dasscofileproxy.webapi.v1;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

@Path("/large-files/{institutionName}/{collectionName}/{assetGuid}/upload{path: (/.*)?}")
public class LargeFiles {
    private static final Logger logger = LoggerFactory.getLogger(LargeFiles.class);
    private final FileService fileService;
    private final TusFileUploadService tusFileUploadService;
    private final java.nio.file.Path uploadDirectory;

    @Inject
    public LargeFiles(FileService fileService) {
        this.fileService = fileService;
        this.tusFileUploadService = new TusFileUploadService()
                .withStoragePath("/temp/upload/tus")
                .withUploadUri("/file_proxy/api/large-files/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/upload");
        this.uploadDirectory = Paths.get("/temp/upload/app");
        try {
            java.nio.file.Files.createDirectories(this.uploadDirectory);
        }
        catch (IOException e) {
            logger.error("create upload directory", e);
        }
    }

    @POST
    public void tusPost(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context HttpServletRequest request, @Context HttpServletResponse response, @Context SecurityContext securityContext){
        this.handleTusFileUpload(request, response, securityContext, institutionName, collectionName, assetGuid);
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
        if (uploadInfo != null && !uploadInfo.isUploadInProgress()) {
            String tusId = uploadURI.substring(uploadURI.lastIndexOf('/') + 1);
            Long fileSize = uploadInfo.getLength();
            String filename = uploadInfo.getMetadata().get("filename");
            String path = uploadInfo.getMetadata().get("path");
            String contentType = new Tika().detect(path);
            FileUploadData fileUploadData = new FileUploadData(assetGuid, institutionName, collectionName, path, Math.toIntExact(fileSize), contentType);
            //this.fileService.createLargeFileUploadInfo(tusId, assetGuid, fileSize, path);

            try (InputStream is = this.tusFileUploadService.getUploadedBytes(uploadURI)) {
                //java.nio.file.Path output = this.uploadDirectory.resolve(uploadInfo.getFileName());
                //java.nio.file.Files.copy(is, output, StandardCopyOption.REPLACE_EXISTING);
                fileService.largeFileUpload(is, fileUploadData, user.keycloakId);
                this.tusFileUploadService.deleteUpload(uploadURI);
            }
            catch (IOException | TusException e) {
                logger.error("get uploaded bytes", e);
                logger.error("delete upload", e);

            }
        }
    }
}
