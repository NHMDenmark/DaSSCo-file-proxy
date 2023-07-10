package dk.northtech.dasscofileproxy.service;

import com.jcraft.jsch.*;
import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import dk.northtech.dasscofileproxy.domain.AssetCache;
import dk.northtech.dasscofileproxy.domain.AssetFull;
import dk.northtech.dasscofileproxy.domain.InternalStatus;
import dk.northtech.dasscofileproxy.domain.SambaServer;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class SFTPService {
    private final SFTPConfig sftpConfig;
    private final Jdbi jdbi;
    private Session session;
    private FileService fileService;
    private DockerConfig dockerConfig;
    private AssetService assetService;
    private SambaServerService sambaServerService;

    private static final Logger logger = LoggerFactory.getLogger(SFTPService.class);

    private final List<SambaServer> filesToMove = new ArrayList<>();

    @Inject
    public SFTPService(SFTPConfig sftpConfig, DataSource dataSource, FileService fileService, DockerConfig dockerConfig, AssetService assetService, @Lazy SambaServerService sambaServerService) {
        this.sftpConfig = sftpConfig;
        this.assetService = assetService;
        this.fileService = fileService;
        this.dockerConfig = dockerConfig;
        this.sambaServerService = sambaServerService;
        this.jdbi = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .registerRowMapper(ConstructorMapper.factory(AssetCache.class));
    }

    public Session open() {
        System.out.println("BEFORE JSch()");
        try {
            JSch jSch = new JSch();

            System.out.println("After JSch()");
            // Add the private key file for authentication
            jSch.addIdentity(sftpConfig.privateKey(), sftpConfig.passphrase());
            System.out.println("jSch");

            System.out.println("Before Session");
            Session session = jSch.getSession(sftpConfig.username(), sftpConfig.host(), sftpConfig.port());
            session.setConfig("PreferredAuthentications", "publickey");
            System.out.println("After Session");

            // Disable strict host key checking
            session.setConfig("StrictHostKeyChecking", "no");

            System.out.println("Connect");
            session.connect();
            System.out.println("After Connect");
            return session;
        } catch (JSchException e) {
            System.out.println("Shii, " + e);
            throw new RuntimeException(e);
        }
    }

    public void disconnect(ChannelSftp channel) {
        channel.exit();
        session.disconnect();
    }

    public ChannelSftp startChannelSftp() {
        System.out.println("BEFORE OPEN()");
        session = open();
        System.out.println("AFTER OPEN");
        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
        } catch (JSchException e) {
            throw new RuntimeException(e);
        }
        return channel;
    }

    public Collection<String> listFiles(String path) throws JSchException, SftpException {
        System.out.println("START");
        List<String> fileList = new ArrayList<>();
        ChannelSftp channel = startChannelSftp();
        System.out.println("AFTER CHANNEL");
        Vector<ChannelSftp.LsEntry> files = channel.ls(path);
        System.out.println("AFTER LS");
        for (ChannelSftp.LsEntry entry : files) {
            if (!entry.getAttrs().isDir()) {
                fileList.add(entry.getFilename());
            }
        }
        System.out.println("BEFORE DC");
        disconnect(channel);

        return fileList;
    }

    @Scheduled(cron = "0 * * * * *")
    public void moveToERDA() {
        logger.info("checking files");
        List<SambaServer> serversToFlush = new ArrayList<>();
        List<String> failedGuids = new ArrayList<>();
        synchronized (filesToMove) {
            serversToFlush.addAll(filesToMove);
            filesToMove.clear();
        }
        for (SambaServer sambaServer : serversToFlush) {
            if (sambaServer.sharedAssets().size() != 1) {
                logger.error("Share that didnt have exactly one asset cannot be moved to ERDA");
            }
            AssetFull fullAsset = assetService.getFullAsset(sambaServer.sharedAssets().get(0).assetGuid());
            String localPath = dockerConfig.mountFolder() + "share_" + sambaServer.sambaServerId() + "/";
            String remotePath = getRemotePath(fullAsset) + "/";
            File newDirectory = new File(dockerConfig.mountFolder() + "share_" + sambaServer.sambaServerId());
            File[] allFiles = newDirectory.listFiles();
            if (allFiles == null) {
                failedGuids.add(fullAsset.guid);
                continue;
            }
            try {
                if (!exists(remotePath)) {
                    makeDirectory(remotePath);
                }
                for (File file : allFiles) {
                    if (!file.isDirectory()) {
                        putFileToPath(localPath + file.getName(), remotePath + file.getName());
                    }
                }
                assetService.completeAsset(fullAsset.guid);
            } catch (Exception e) {
                failedGuids.add(fullAsset.guid);
                throw new RuntimeException(e);
            }
            for (String s : failedGuids) {
                assetService.setFailedStatus(s, InternalStatus.ERDA_ERROR);
            }
        }
    }

    public void putFileToPath(String localPath, String remotePath) throws JSchException {
        ChannelSftp channel = startChannelSftp();
        try {
            channel.put(localPath, remotePath);
            disconnect(channel);
        } catch (SftpException e) {
            throw new RuntimeException(e);
        }
        disconnect(channel);
    }

    public boolean makeDirectory(String path) throws SftpException {
        ChannelSftp channel = startChannelSftp();

        // Create the empty folder
        channel.mkdir(path);

        SftpATTRS attrs = channel.stat(path);
        boolean exists = attrs.isDir();

        disconnect(channel);
        return exists;
    }

    public boolean exists(String path) throws IOException, SftpException {
        ChannelSftp channel = startChannelSftp();
        String parentPath = path.substring(0, path.lastIndexOf('/'));

        // List the contents of the parent directory
        Vector<ChannelSftp.LsEntry> entries = channel.ls(parentPath);

        // Iterate through the entries to check if the file or folder exists
        for (ChannelSftp.LsEntry entry : entries) {
            if (entry.getFilename().equals(path)) {
                // File or folder exists
                return true;
            }
        }

        // File or folder does not exist
        return false;
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

    public String getRemotePath(String institution, String collection, String assetGuid) {
        return sftpConfig.remoteFolder() + institution + "/" + collection + "/" + assetGuid;
    }

    public String getRemotePath(AssetFull asset) {
        return sftpConfig.remoteFolder() + asset.institution + "/" + asset.collection + "/" + asset.guid;
    }

    public String getLocalFolder(String institution, String collection, String assetGuid) {
        return sftpConfig.localFolder() + institution + "/" + collection + "/" + assetGuid;
    }

    public void initAssetShare(String sharePath, String assetGuid) {
        AssetFull asset = assetService.getFullAsset(assetGuid);
        String remotePath = getRemotePath(asset);
        try {
            Collection<String> fileNames = listFiles(remotePath);
            for (String s : fileNames) {
                if (!Files.exists(Path.of(sharePath + "/" + s))) {

                logger.info("Downloading from " + remotePath + "/" + s);
                downloadFile(remotePath + "/" + s, sharePath);
                }
            }
            //If asset have parent download into parent folder
            if (asset.parent_guid != null) {
                AssetFull parent = assetService.getFullAsset(asset.parent_guid);
                String parentPath = getRemotePath(parent) + "/";
                Collection<String> parentFileNames = listFiles(remotePath);
                File parentDir = new File(sharePath + "/parent/");
                if(!parentDir.exists()) {
                    parentDir.mkdir();
                }
                for (String s : parentFileNames) {
                    if (!Files.exists(Path.of(sharePath + "/" + s))) {
                        logger.info("Downloading from " + parentPath + s);
                        downloadFile(parentPath + s, sharePath);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void cacheFile(String remotePath, String localPath) {
        try {

            String parentPath = localPath.substring(0, localPath.lastIndexOf('/'));

            Files.createDirectories(Path.of(parentPath));

            this.downloadFile(remotePath, localPath);

            long savedFileSize = Files.size(Path.of(localPath));

            // Determine expiration datetime based on file size
            AssetCache assetCache;
            var now = LocalDateTime.now();
            if (savedFileSize < 10 * 1000000) {
                assetCache = new AssetCache(0L, localPath, savedFileSize, now.plusMonths(3), now);
            } else if (savedFileSize < 50 * 1000000) {
                assetCache = new AssetCache(0L, localPath, savedFileSize, now.plusWeeks(1), now);
            } else {
                assetCache = new AssetCache(0L, localPath, savedFileSize, now.plusDays(1), now);
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
        } catch (IOException e) {
            e.printStackTrace();
        } catch (SftpException e) {
            throw new RuntimeException(e);
        }
    }
}
