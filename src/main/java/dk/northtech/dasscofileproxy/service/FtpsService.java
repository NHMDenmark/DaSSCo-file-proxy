package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.assets.ErdaProperties;
import dk.northtech.dasscofileproxy.domain.AssetCache;
import jakarta.inject.Inject;
import org.apache.commons.net.PrintCommandListener;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;


@Service
public class FtpsService {
    private String server;
    private int port;
    private String user;
    private String password;
    private FTPSClient ftps;
    private final Jdbi jdbi;

    @Inject
    public FtpsService(ErdaProperties properties, DataSource dataSource) {
        // Initialize FTP service with properties
        this.server = properties.server();
        this.port = properties.port();
        this.user = properties.user();
        this.password = properties.password();

        this.jdbi = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .registerRowMapper(ConstructorMapper.factory(AssetCache.class));
    }

    public FtpsService(String server, int port, String user, String password) {
        // Initialize FTP service parameter values
        this.server = server;
        this.port = port;
        this.user = user;
        this.password = password;
        this.jdbi = null;
    }

    /**
     * Opens the FTP service connection.
     */
    public void open() {
        try {
            // Create and configure FTPSClient
            ftps = new FTPSClient();
            ftps.addProtocolCommandListener(new PrintCommandListener(new PrintWriter(System.out)));

            // Connect to FTP server
            ftps.connect(server, port);
            int reply = ftps.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftps.disconnect();
                throw new IOException("Exception in connecting to FTP Server");
            }

            // Login to the FTP server
            boolean loggedIn = ftps.login(user, password);
            if (!loggedIn) {
                throw new IOException("Failed to log in to the FTP Server");
            }

            // Set passive mode
            ftps.enterLocalPassiveMode();

            // Set binary filetype
            ftps.setFileType(FTP.BINARY_FILE_TYPE);

            // Enable FTPS (implicit/explicit)
            ftps.execPBSZ(0);
            ftps.execPROT("P");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Closes the FTP service connection.
     *
     * @throws IOException if an I/O error occurs.
     */
    public void close() throws IOException {
        ftps.logout();
        ftps.disconnect();
    }

    /**
     * Lists files in the specified path on the FTP server.
     *
     * @param path the path to list files from.
     * @return a collection of file names.
     * @throws IOException if an I/O error occurs.
     */
    public Collection<String> listFiles(String path) throws IOException {
        FTPFile[] files = ftps.listFiles(path);
        return Arrays.stream(files)
                .map(FTPFile::getName)
                .collect(Collectors.toList());
    }

    /**
     * Uploads a file to the specified path on the FTP server.
     *
     * @param file the file to upload.
     * @param path the path to upload the file to.
     * @throws IOException if an I/O error occurs.
     */
    public void putFileToPath(File file, String path) throws IOException {
        try (InputStream inputStream = new FileInputStream(file)) {
            ftps.storeFile(path, inputStream);
        }
    }

    /**
     * Creates a directory on the FTP server.
     *
     * @param path the path of the directory to create.
     * @return true if the directory was successfully created, false otherwise.
     * @throws IOException if an I/O error occurs.
     */
    public boolean makeDirectory(String path) throws IOException {
        return ftps.makeDirectory(path);
    }

    /**
     * Retrieves the files in the specified path on the FTP server.
     *
     * @param path the path to retrieve files from.
     * @return an array of FTPFile objects representing the files.
     * @throws IOException if an I/O error occurs.
     */
    public FTPFile[] getFiles(String path) throws IOException {
        return ftps.listFiles(path);
    }

    /**
     * Checks if a file or directory exists at the specified path on the FTP server.
     *
     * @param path the path to check for existence.
     * @return true if the file or directory exists, false otherwise.
     * @throws IOException if an I/O error occurs.
     */
    public boolean exists(String path) throws IOException {
        FTPFile[] files = ftps.listFiles(path);
        return files != null && files.length > 0;
    }

    /**
     * Downloads a file from the FTP server to the specified destination.
     *
     * @param path      the path of the file to download on the FTP server.
     * @return InputStream for the file
     * @throws IOException if an I/O error occurs.
     */
    public InputStream downloadFile(String path) throws IOException {
        return ftps.retrieveFileStream(path);
    }

    /**
     * Sets the underlying FTPSClient instance for the FTP service.
     *
     * @param ftpsClient the FTPSClient instance to set.
     */
    public void setFtpsClient(FTPSClient ftpsClient) {
        this.ftps = ftpsClient;
    }

    /**
     * Retrieves a cached file as an InputStream.
     *
     * @param path the path of the cached file.
     * @return the InputStream for the cached file, or null if the file is not found.
     */
    public InputStream getCached(String path) {
        File file = new File("cached", path);
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    /**
     * Refreshes the time-to-live (expiration) of the specified cached file.
     *
     * @param path the path of the cached file.
     */
    public void refreshTimeToLive(String path) {
        this.jdbi.inTransaction(h -> {
            var asset = h.createQuery("SELECT * FROM asset_caches WHERE asset_path = :path ORDER BY creation_datetime ASC LIMIT 1")
                    .bind("path", path)
                    .mapTo(AssetCache.class)
                    .one();

            LocalDateTime expirationDateTime;
            var now = LocalDateTime.now();
            if (asset.fileSize() < 10 * 1000000) {
                expirationDateTime = now.plusMonths(3);
            } else if (asset.fileSize() < 50 * 1000000) {
                expirationDateTime = now.plusWeeks(1);
            } else {
                expirationDateTime = now.plusDays(1);
            }

            h.createUpdate("UPDATE asset_caches SET expiration_datetime = :expiration WHERE asset_path = :path")
                    .bind("expiration", expirationDateTime)
                    .bind("path", path)
                    .executeAndReturnGeneratedKeys()
                    .mapTo(AssetCache.class)
                    .list();
            return null;
        });
    }

    /**
     * Caches a file by saving it to the specified path.
     *
     * @param path the path to save the file to.
     * @param file the InputStream containing the file data.
     * @return true if the file was successfully cached, false otherwise.
     */
    public boolean cacheFile(String path, InputStream file) {
        File tempFile = null;
        try {
            // Create a temporary file to save the InputStream
            tempFile = File.createTempFile("temp", null);

            // Copy the InputStream to the temporary file
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = file.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            // Check if the size of the saved file matches the InputStream size
            long inputStreamSize = tempFile.length();
            long savedFileSize = Files.size(tempFile.toPath());
            if (inputStreamSize == savedFileSize) {
                // Create the target directory and parent directories if they don't exist
                Path targetDir = Path.of("cached").resolve(Path.of(path).getParent());
                Files.createDirectories(targetDir);

                // Move the temporary file to the desired location
                Files.move(tempFile.toPath(), targetDir.resolve(Path.of(path).getFileName()), StandardCopyOption.REPLACE_EXISTING);

                // Determine expiration datetime based on file size
                AssetCache assetCache;
                var now = LocalDateTime.now();
                if (savedFileSize < 10 * 1000000) {
                    assetCache = new AssetCache(0L, path, savedFileSize, now.plusMonths(3), now);
                } else if (savedFileSize < 50 * 1000000) {
                    assetCache = new AssetCache(0L, path, savedFileSize, now.plusWeeks(1), now);
                } else {
                    assetCache = new AssetCache(0L, path, savedFileSize, now.plusDays(1), now);
                }

                // Insert the AssetCache into the database
                this.jdbi.inTransaction(h -> h
                        .createUpdate("""
                                INSERT INTO asset_caches (asset_path, file_size, expiration_datetime, creation_datetime)
                                VALUES (:assetPath, :fileSize, :expirationDatetime, :creationDatetime)
                                """)
                        .bindMethods(assetCache)
                        .executeAndReturnGeneratedKeys()
                        .mapTo(AssetCache.class)
                        .findOne()
                );
                return true;
            } else {
                // Delete the potential file saved due to size mismatch
                tempFile.delete();
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            // Error occurred, delete the potential file saved
            if (tempFile != null) {
                tempFile.delete();
            }
            return false;
        }
    }

    /**
     * Removes expired cached files from the file system and the database.
     */
    public void removedExpiredCaches() {
        this.jdbi.inTransaction(h -> {
            var caches = h.createQuery("SELECT * FROM asset_caches")
                    .mapTo(AssetCache.class)
                    .list();
            var now = LocalDateTime.now();
            caches.forEach(cache -> {
                if (cache.expirationDatetime().isBefore(now)) {
                    var file = new File("cached", cache.assetPath());
                    if (file.delete() || !file.exists()) {
                        h.createUpdate("DELETE FROM asset_caches WHERE asset_cache_id = :id")
                                .bind("id", cache.assetCacheId())
                                .execute();
                    }
                }
            });
            return null;
        });
    }
}