package dk.northtech.dasscofileproxy.webapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Path("/large-files/upload{path: (/.*)?}")
public class LargeFiles {
    private static final Logger logger = LoggerFactory.getLogger(LargeFiles.class);
    private final TusFileUploadService tusFileUploadService;
    private final java.nio.file.Path uploadDirectory;

    public LargeFiles() {
        this.tusFileUploadService = new TusFileUploadService()
                .withStoragePath("/temp/upload/tus")
                .withUploadUri("/file_proxy/api/large-files/upload");
        this.uploadDirectory = Paths.get("/temp/upload/app");
        try {
            java.nio.file.Files.createDirectories(this.uploadDirectory);
        }
        catch (IOException e) {
            logger.error("create upload directory", e);
        }
    }

    @GET
    public void tusGet(@Context HttpServletRequest request, @Context HttpServletResponse response) {
        this.handleTusFileUpload(request, response);
    }

    @POST
    public void tusPost(@Context HttpServletRequest request, @Context HttpServletResponse response){
        this.handleTusFileUpload(request, response);
    }

    @PUT
    public void tusPut(@Context HttpServletRequest request, @Context HttpServletResponse response){
        this.handleTusFileUpload(request, response);
    }

    @DELETE
    public void tusDelete(@Context HttpServletRequest request, @Context HttpServletResponse response){
        this.handleTusFileUpload(request, response);
    }

    @PATCH
    @Consumes("application/offset+octet-stream")
    public void tusPatch(@Context HttpServletRequest request, @Context HttpServletResponse response){
        this.handleTusFileUpload(request, response);
    }

    @HEAD
    public void tusHead(@Context HttpServletRequest request, @Context HttpServletResponse response){
        this.handleTusFileUpload(request, response);
    }

    @OPTIONS
    public void tusOptions(@Context HttpServletRequest request, @Context HttpServletResponse response){
        this.handleTusFileUpload(request, response);
    }

    private void handleTusFileUpload(HttpServletRequest request, HttpServletResponse response) {
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
            try (InputStream is = this.tusFileUploadService.getUploadedBytes(uploadURI)) {
                java.nio.file.Path output = this.uploadDirectory.resolve(uploadInfo.getFileName());
                java.nio.file.Files.copy(is, output, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException | TusException e) {
                logger.error("get uploaded bytes", e);
            }

            try {
                this.tusFileUploadService.deleteUpload(uploadURI);
            }
            catch (IOException | TusException e) {
                logger.error("delete upload", e);
            }
        }
    }
}
