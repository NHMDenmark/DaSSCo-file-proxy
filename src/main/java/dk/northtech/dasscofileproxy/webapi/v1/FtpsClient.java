package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.Asset;
import dk.northtech.dasscofileproxy.service.FtpsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.net.ftp.FTPFile;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Path("/v1/ftps")
public class FtpsClient {

    private final FtpsService ftpsService;

    @Inject
    public FtpsClient(FtpsService ftpsService) {
        this.ftpsService = ftpsService;
    }

    @GET
    @Path("listfiles")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<String> test(@QueryParam("path") String path) throws IOException {
        System.out.println("opening connection");
        this.ftpsService.open();
        System.out.println("getting files");
        var files = this.ftpsService.listFiles(path);
        System.out.println("closing connection");
        this.ftpsService.close();
        return files;
    }

    @PUT
    @Path("upload/{path}")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean update(@PathParam("path") String path, Asset asset) {
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
            File folder = new File(path);
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
                                throw new RuntimeException("Sizes of remote and local files do not match. Aborting. Note that files have still been uploaded to the server.");
                            }
                        }
                    }
                }

                // Delete the local files
                for (File file : files) {
                    var deleted = file.delete();
                    if (!deleted) {
                        throw new RuntimeException("Couldn't delete file: " + file.getName() + ".");
                    }
                }

                // Delete the local folder
                var deleted = folder.delete();
                if (!deleted) {
                    throw new RuntimeException("Couldn't delete folder: " + folder.getName() + ".");
                }
            }

            // Close the FTP service connection
            this.ftpsService.close();
            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
