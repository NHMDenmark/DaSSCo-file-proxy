package dk.northtech.dasscofileproxy.service;

import com.jcraft.jsch.*;
import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

public class ERDAClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ERDAClient.class);
    private Session session;

    private final SFTPConfig sftpConfig;
    private ErdaDataSource creator;

    public ERDAClient(SFTPConfig sftpConfig) {
        this.sftpConfig = sftpConfig;
        try {
            JSch jSch = new JSch();
            // Add the private key file for authentication
            jSch.addIdentity(sftpConfig.privateKey(), sftpConfig.passphrase());
            logger.info("Added credz");

            session = jSch.getSession(sftpConfig.username(), sftpConfig.host(), sftpConfig.port());
            session.setConfig("PreferredAuthentications", "publickey");
            logger.info("Got sesh");
            // Disable strict host key checking
            session.setConfig("StrictHostKeyChecking", "no");

            session.connect(10000);

            logger.info("Connected");
        } catch (JSchException e) {
            logger.error("Failed to connect to ERDA: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public ERDAClient(SFTPConfig sftpConfig, ErdaDataSource erdaDataSource) {
        this.sftpConfig = sftpConfig;
        this.creator = erdaDataSource;
        try {
            JSch jSch = new JSch();
            // Add the private key file for authentication
            jSch.addIdentity(sftpConfig.privateKey(), sftpConfig.passphrase());
            logger.info("Added credz");

            session = jSch.getSession(sftpConfig.username(), sftpConfig.host(), sftpConfig.port());
            session.setConfig("PreferredAuthentications", "publickey");
            logger.info("Got sesh");
            // Disable strict host key checking
            session.setConfig("StrictHostKeyChecking", "no");

            session.connect(10000);

            logger.info("Connected");
        } catch (JSchException e) {
            logger.error("Failed to connect to ERDA: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void restore() {
        try {
            JSch jSch = new JSch();
            // Add the private key file for authentication
            jSch.addIdentity(sftpConfig.privateKey(), sftpConfig.passphrase());
            logger.info("Added credz");

            this.session = jSch.getSession(sftpConfig.username(), sftpConfig.host(), sftpConfig.port());
            this.session.setConfig("PreferredAuthentications", "publickey");
            logger.info("Got sesh");
            // Disable strict host key checking
            this.session.setConfig("StrictHostKeyChecking", "no");

            this.session.connect();

            logger.info("Connected");
        } catch (JSchException e) {
            logger.error("Failed to connect to ERDA: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void disconnect(ChannelSftp channel) {
        channel.exit();
    }

    public ChannelSftp startChannelSftp() {
        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
        } catch (JSchException e) {
            throw new RuntimeException(e);
        }
        return channel;
    }

    @Override
    public void close() throws Exception {
        if (this.creator != null) {
            creator.recycle(this);
        } else {
            session.disconnect();
        }

    }

    public Collection<String> listFiles(String path) throws JSchException, SftpException {
        List<String> fileList = new ArrayList<>();
        ChannelSftp channel = startChannelSftp();
        Vector<ChannelSftp.LsEntry> files = channel.ls(path);
        for (ChannelSftp.LsEntry entry : files) {
            if (!entry.getAttrs().isDir()) {
                fileList.add(entry.getFilename());
            }
        }
        disconnect(channel);

        return fileList;
    }

    public Set<String> putFilesOnRemotePathBulk(List<File> files, String localMountFolder, String remotePath) {
        ChannelSftp channel = startChannelSftp();
        try {
            HashSet<String> uploadedFiles = new HashSet<>();
            for (File file : files) {
                String fullRemotePath = remotePath + file.toPath().toString().replace("\\", "/").replace(localMountFolder, "");
                logger.info("moving from localPath {}, to remotePath {}", file.getPath(), fullRemotePath);
                channel.put(file.getPath(), fullRemotePath);
                uploadedFiles.add(fullRemotePath);
            }
            return uploadedFiles;
        } catch (SftpException e) {
            throw new RuntimeException(e);
        } finally {
            disconnect(channel);
        }
    }

    public void deleteFiles(List<String> filesToDelete) {
        ChannelSftp channelSftp = startChannelSftp();
        try {
            for (String filePath : filesToDelete) {
                logger.info("Deleting remote file {}", filePath);
                channelSftp.rm(filePath);
            }
        } catch (SftpException e) {
            throw new RuntimeException(e);
        } finally {
            channelSftp.disconnect();
        }
    }

    public void createSubDirsIfNotExists(List<Path> paths) {
        ChannelSftp channelSftp = startChannelSftp();
        for (Path path : paths) {
            //Last element is the file itself
            int directoryDepth = path.getNameCount() - 1;
            String remotePath = "";
            for (int i = 0; i < directoryDepth; i++) {
                remotePath += path.getName(i) + "/";
                try {
                    channelSftp.mkdir(remotePath);
                } catch (Exception e) {
                    //OK, the folder already exists
                }
            }
        }

    }

    public void putFileToPath(String localPath, String remotePath) throws JSchException {
        ChannelSftp channel = startChannelSftp();
        try {
            channel.put(localPath, remotePath);
        } catch (SftpException e) {
            throw new RuntimeException(e);
        } finally {
            disconnect(channel);
        }
    }


    public boolean exists(String path, boolean isFolder) throws IOException, SftpException {
        // List the contents of the parent directory
        ChannelSftp channel = startChannelSftp();
        try {
            String parentPath = path.substring(0, path.lastIndexOf('/'));
            Vector<ChannelSftp.LsEntry> entries = channel.ls(parentPath);
            if (isFolder) {
                if (entries.size() > 0) {
                    return true;
                }
            }
            // Iterate through the entries to check if the file or folder exists
            for (ChannelSftp.LsEntry entry : entries) {
                String[] split = path.split("/");
                if (entry.getFilename().equals(split[split.length - 1])) {
                    // File or folder exists
                    return true;
                }
//                if {
//
//                }
            }
        } catch (Exception e) {
            channel.disconnect();
            // File or folder does not exist
            return false;
        }

        return false;
    }

    //Recursively get all files
    public List<String> listAllFiles(String path) {
        ChannelSftp channel = startChannelSftp();
        try {
            return listFolder(new ArrayList<>(), path, channel);
        } catch (SftpException e) {
            throw new RuntimeException("Failed to list all files", e);
        } finally {
            channel.disconnect();
        }
    }

    public List<String> listFolder(List<String> foundFiles, String path, ChannelSftp channel) throws SftpException {
        Vector<ChannelSftp.LsEntry> files = channel.ls(path);
        for (ChannelSftp.LsEntry entry : files) {
            if (!entry.getAttrs().isDir()) {
                if (path.endsWith("/")) {
                    foundFiles.add(path + entry.getFilename());
                } else {
                    foundFiles.add(path + "/" + entry.getFilename());
                }
//                foundFiles.add(path +  entry.getFilename());
            } else {
                if (path.endsWith("/")) {
                    listFolder(foundFiles, path + entry.getFilename(), channel);
                } else {
                    listFolder(foundFiles, path + "/" + entry.getFilename(), channel);
                }
            }
        }
        return foundFiles;
    }

    //Takes a list of file locations and downloads the files
    public void downloadFiles(List<String> locations, String destination, String asset_guid) {
        ChannelSftp channel = startChannelSftp();
        try {
            for (String location : locations) {

                String destinationLocation = destination + location.substring(location.indexOf(asset_guid) + asset_guid.length());
                logger.info("Getting from {} saving in {}", location, destinationLocation);
                File parentDir = new File(destinationLocation.substring(0, destinationLocation.lastIndexOf('/')));
                if (!parentDir.exists()) {
                    parentDir.mkdirs();
                }
                channel.get(location, destinationLocation);

            }
        } catch (SftpException e) {
            throw new RuntimeException(e);
        } finally {
            channel.disconnect();
        }
    }

    public InputStream getFileInputStream(String path) throws IOException, SftpException {
        ChannelSftp channel = startChannelSftp();
        return channel.get(path);
    }

    public void downloadFile(String path, String destination) throws IOException, SftpException {
        ChannelSftp channel = startChannelSftp();
        channel.get(path, destination);
        disconnect(channel);
    }

    public void testAndRestore() {
        try {
//            logger.info("Verifying that ERDA connection works");
            Collection<String> strings = listFiles("healthcheck/");
        } catch (Exception e) {
            //Try to restore connection if connection fails
            try {
                logger.info("Trying to restore failed ERDA SFTP connection");
                restore();
            } catch (Exception e2) {
                logger.warn("Failed to restore ERDA SFTP connection");
                // We do not want to return failed connection.
                throw new RuntimeException(e2);
            }
        }
    }
}
