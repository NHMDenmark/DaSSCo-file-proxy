package dk.northtech.dasscofileproxy.service;

import com.jcraft.jsch.*;
import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import dk.northtech.dasscofileproxy.domain.AssetCache;
import jakarta.inject.Inject;
import org.apache.commons.net.ftp.FTPFile;
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
import java.util.*;
import java.util.stream.Collectors;


@Service
public class SFTPService {
    private final SFTPConfig sftpConfig;
    private final Jdbi jdbi;
    private Session session;

    @Inject
    public SFTPService(SFTPConfig sftpConfig, DataSource dataSource) {
        this.sftpConfig = sftpConfig;
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

    public String getLocalFolder(String institution, String collection, String assetGuid) {
        return sftpConfig.localFolder() + institution + "/" + collection + "/" + assetGuid;
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
