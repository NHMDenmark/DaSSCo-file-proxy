package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.Asset;
import dk.northtech.dasscofileproxy.service.FtpsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.net.ftp.FTPFile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;

@Component
@Path("/v1/ftps")
public class FtpsClient {

    private final FtpsService ftpsService;

    @Inject
    public FtpsClient(FtpsService ftpsService) {
        this.ftpsService = ftpsService;
    }


    @GET
    @Path("listfiles/{path : .+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<String> test(@Encoded @PathParam("path") String path) throws IOException {
        this.ftpsService.open();
        var files = this.ftpsService.listFiles("DaSSCoStorage/" + path);
        this.ftpsService.close();
        return files;
    }
    @GET
    @Path("{path : .+}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response get(@Encoded @PathParam("path") String path) throws IOException {
        // Check if the file is already cached
        InputStream cached = ftpsService.getCached(path);
        if (cached != null) {
            // Refresh the time to live for the cached file
            ftpsService.refreshTimeToLive(path);
            return Response.ok(cached).build(); // Return the cached file
        }

        // Open the FTP service connection
        ftpsService.open();

        // Download the file from the FTP server
        InputStream fileStream = ftpsService.downloadFile("DaSSCoStorage/" + path);
        if (fileStream == null) {
            // File not found on the FTP server
            return Response.status(404).entity("The requested resource could not be found.").build();
        }

        // Read the file content into a byte array
        byte[] bytes = fileStream.readAllBytes();

        // Close the FTP service connection
        ftpsService.close();

        // Cache the file locally
        ftpsService.cacheFile(path, new ByteArrayInputStream(bytes));

        // Return the file as a response
        return Response.ok(new ByteArrayInputStream(bytes)).build();
    }



    @PUT
    @Path("upload")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean update(Asset asset) {
        try {
            // Open the FTPS service connection
            this.ftpsService.open();

            // Construct the paths for institution, collection, and asset
            String institutionPath = "DaSSCoStorage/" + asset.institution();
            String collectionPath = institutionPath + "/" + asset.collection();
            String assetPath = collectionPath + "/" + asset.assetGuid();

            // Create the institution directory if it doesn't exist
            if (!this.ftpsService.exists(institutionPath)) {
                this.ftpsService.makeDirectory(institutionPath);
            }

            // Create the collection directory if it doesn't exist
            if (!this.ftpsService.exists(collectionPath)) {
                this.ftpsService.makeDirectory(collectionPath);
            }

            // Create the asset directory
            this.ftpsService.makeDirectory(assetPath);

            // Get the files from the local folder
            File folder = new File("DaSSCo_upload");
            File[] files = folder.listFiles();

            // Check if there are files in the folder
            if (files != null) {
                // Upload each file to the asset directory
                for (File file : files) {
                    if (!file.isDirectory()) {
                        String filePath = assetPath + "/" + file.getName();
                        this.ftpsService.putFileToPath(file, filePath);
                    }
                }

                // Get the list of remote files in the asset directory
                List<FTPFile> remoteFiles = Arrays.asList(this.ftpsService.getFiles(assetPath));

                // Compare the sizes of local and remote files
                for (File localFile : files) {
                    if (!localFile.isDirectory()) {
                        String fileName = localFile.getName();
                        Optional<FTPFile> remoteFile = remoteFiles.stream()
                                .filter(f -> f.getName().equals(fileName))
                                .findFirst();

                        if (remoteFile.isPresent()) {
                            if (localFile.length() != remoteFile.get().getSize()) {
                                throw new RuntimeException("Sizes of remote and local files do not match. Size of local file is: " + localFile.length() + ", size of remote file is: " + remoteFile.get().getSize());
                            }
                        }
                    }
                }

                // Delete the local files
                for (File file : files) {
                    if (!file.isDirectory()) {
                        var deleted = file.delete();
                        if (!deleted) {
                            throw new RuntimeException("Couldn't delete file: " + file.getName() + ".");
                        }
                    }
                }
            }

            // Close the FTP service connection
            this.ftpsService.close();
            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Scheduled(cron = "0 0 * * * *") // hourly
    public void removedExpiredCaches() {
        this.ftpsService.removedExpiredCaches();
    }
}
