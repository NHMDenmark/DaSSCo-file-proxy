package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.assets.ErdaProperties;
import jakarta.inject.Inject;
import org.apache.commons.net.PrintCommandListener;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.stereotype.Service;

import java.io.*;
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

    @Inject
    public FtpsService(ErdaProperties properties) {
        // Initialize FTP service with properties
        this.server = properties.server();
        this.port = properties.port();
        this.user = properties.user();
        this.password = properties.password();
    }

    public FtpsService(String server, int port, String user, String password) {
        // Initialize FTP service parameter values
        this.server = server;
        this.port = port;
        this.user = user;
        this.password = password;
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
     * @param source      the path of the file to download on the FTP server.
     * @param destination the local destination to save the downloaded file.
     * @throws IOException if an I/O error occurs.
     */
    public void downloadFile(String source, String destination) throws IOException {
        try (FileOutputStream out = new FileOutputStream(destination)) {
            ftps.retrieveFile(source, out);
        }
    }

    public void setFtpsClient(FTPSClient ftpsClient) {
        this.ftps=ftpsClient;
    }
}