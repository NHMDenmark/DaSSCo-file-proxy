package dk.northtech.dasscofileproxy.service;

import com.jcraft.jsch.*;
import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.repository.DirectoryRepository;
import dk.northtech.dasscofileproxy.repository.SharedAssetList;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class SFTPService {
    private final SFTPConfig sftpConfig;
    private final Jdbi jdbi;
    private Session session;
    private FileService fileService;
    private ShareConfig shareConfig;
    private AssetService assetService;
//    private HttpShareService httpShareService;
    private static final Logger logger = LoggerFactory.getLogger(SFTPService.class);

    @Inject
    public SFTPService(SFTPConfig sftpConfig, DataSource dataSource, FileService fileService, ShareConfig shareConfig, AssetService assetService, Jdbi jdbi) {
        this.sftpConfig = sftpConfig;
        this.assetService = assetService;
        this.fileService = fileService;
        this.shareConfig = shareConfig;
        this.jdbi = jdbi;
//                Jdbi.create(dataSource)
//                .installPlugin(new SqlObjectPlugin())
//                .registerRowMapper(ConstructorMapper.factory(AssetCache.class));
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

    public void moveToERDA(AssetUpdate assetUpdate) {
        Optional<Directory> writeableDirectory = fileService.getWriteableDirectory(assetUpdate.assetGuid());
        if (writeableDirectory.isPresent()) {
           fileService.scheduleDirectoryForSynchronization(writeableDirectory.get().directoryId(), assetUpdate);
        } else {
            throw new IllegalArgumentException("No writeable share for asset found");
        }
    }

    public List<Directory> getHttpSharesToSynchronize(int maxAttempts) {
        return jdbi.withHandle(h -> {
            DirectoryRepository attach = h.attach(DirectoryRepository.class);
            return attach.getDirectoriesForSynchronization(maxAttempts);

        });
    }

    public List<SharedAsset> getShardAsset(long directoryId) {
        return jdbi.withHandle(h -> {
            SharedAssetList attach = h.attach(SharedAssetList.class);
            return attach.getSharedAssetsByDirectory(directoryId);
        });
    }
    @Scheduled(cron = "0 * * * * *")
    public void moveFiles() {
        logger.info("checking files");
        List<Directory> directories = getHttpSharesToSynchronize(5);
        List<String> failedGuids = new ArrayList<>();

//        synchronized (filesToMove) {
//            serversToFlush = new ArrayList<>(filesToMove);
//            filesToMove.clear();
//        }
        for (Directory directory : directories) {
            List<SharedAsset> sharedAssetList = getShardAsset(directory.directoryId());
            if(sharedAssetList.size() != 1) {
                throw new RuntimeException("Directory has multiple shared assets");
            }
            SharedAsset sharedAsset = sharedAssetList.get(0);

            AssetFull fullAsset = assetService.getFullAsset(sharedAsset.assetGuid());
            if(fullAsset.asset_locked) {
                logger.info("Asset {} is locked", sharedAsset.assetGuid());
                failedGuids.add(fullAsset.asset_guid);
            }
            String remotePath = getRemotePath(new MinimalAsset(fullAsset.asset_guid, fullAsset.parent_guid, fullAsset.institution, fullAsset.collection));
            String localMountFolder = this.shareConfig.mountFolder() + directory.uri();
            File localDirectory = new File(shareConfig.mountFolder() + directory.uri());
            List<File> files = fileService.listFiles(localDirectory, new ArrayList<>(),false, false);
            List<Path> remoteLocations = files.stream().map(file -> {
                logger.info("Remote path is: " + remotePath);
                logger.info("Local base path is: " + localMountFolder);
                logger.info("File path is: " + file.toPath().toString().replace("\\", "/"));
                return Path.of(remotePath + "/" + file.toPath().toString().replace("\\", "/").replace(localMountFolder, ""));
            }).collect(Collectors.toList());
            createSubDirsIfNotExists(remoteLocations);
            List<String> remoteFiles = listAllFiles(remotePath);
//            }
            try {
                final Set<String> uploadedFiles = putFilesOnRemotePathBulk(files, localMountFolder, remotePath);
                //handle files that have been deleted
                List<String> filesToDelete = remoteFiles.stream().filter(f -> !uploadedFiles.contains(f)).collect(Collectors.toList());
                deleteFiles(filesToDelete);
                if (assetService.completeAsset(new AssetUpdateRequest(null, new MinimalAsset(sharedAsset.assetGuid(),null,null, null), directory.syncWorkstation(), directory.syncPipeline(), directory.syncUser()))) {
                    fileService.deleteDirectory(directory.directoryId());
                    fileService.removeShareFolder(directory);
                }
            } catch (Exception e) {
                failedGuids.add(fullAsset.asset_guid);
                throw new RuntimeException(e);
            }
        }
        for (String s : failedGuids) {
            assetService.setFailedStatus(s, InternalStatus.ERDA_ERROR);
        }
    }

    private Set<String> putFilesOnRemotePathBulk(List<File> files, String localMountFolder, String remotePath) {
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

    private void deleteFiles(List<String> filesToDelete) {
        ChannelSftp channelSftp = startChannelSftp();
        try {
            for (String filePath : filesToDelete) {
                channelSftp.rm(filePath);
            }
        } catch (SftpException e) {
            throw new RuntimeException(e);
        } finally {
            channelSftp.disconnect();
        }
    }

    private void createSubDirsIfNotExists(List<Path> paths) {
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

    public boolean makeDirectory(String path) throws SftpException {
        ChannelSftp channel = startChannelSftp();

        // Create the empty folder
        channel.mkdir(path);

        SftpATTRS attrs = channel.stat(path);
        boolean exists = attrs.isDir();

        disconnect(channel);
        return exists;
    }

    public boolean exists(String path, boolean isFolder) throws IOException, SftpException {
        // List the contents of the parent directory
        ChannelSftp channel = startChannelSftp();
        try {
            String parentPath = path.substring(0, path.lastIndexOf('/'));
            Vector<ChannelSftp.LsEntry> entries = channel.ls(parentPath);
            if(isFolder) {
                if (entries.size() >0) {
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
            System.out.println("Found " + entry.getFilename());
            if (!entry.getAttrs().isDir()) {
                foundFiles.add(path + "/" + entry.getFilename());
            } else {
                listFolder(foundFiles, path + "/" + entry.getFilename(), channel);
            }
        }
        return foundFiles;
    }

    //Takes a list of file locations and downloads the files
    public void downloadFiles(List<String> locations, String destination, String asset_guid) {
        ChannelSftp channel = startChannelSftp();
        try {
            for (String location : locations) {
                //remove institution/collection/guid from local path //TODO we use nested structure locally now... i think
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

    public String getRemotePath(String institution, String collection, String assetGuid) {
        return sftpConfig.remoteFolder() + institution + "/" + collection + "/" + assetGuid;
    }

    public String getRemotePath(MinimalAsset asset) {
        return sftpConfig.remoteFolder() + asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid() + "/";
    }

    public List<String> getRemotePathElements(AssetFull asset) {
        return Arrays.asList(sftpConfig.remoteFolder(), asset.institution, asset.collection, asset.asset_guid);
    }

    public String getLocalFolder(String institution, String collection, String assetGuid) {
        return sftpConfig.localFolder() + institution + "/" + collection + "/" + assetGuid;
    }

    public void initAssetShare(String sharePath, MinimalAsset minimalAsset) {
//        AssetFull asset = assetService.getFullAsset(assetGuid);
        String remotePath = getRemotePath(minimalAsset);
        logger.info("Initialising asset folder, remote path is {}", remotePath);
        try {
            if (!exists(remotePath, true)) {
                logger.info("Remote path {} didnt exist ", remotePath);
            } else {
                List<String> fileNames = listAllFiles(remotePath);
                downloadFiles(fileNames, sharePath, minimalAsset.asset_guid());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            logger.info("Initialising parent folder, parent_guid is {}", minimalAsset.parent_guid());

            //If asset have parent download into parent folder
            //We could save a http request here as we dont need the full parent asset to get the remote location, it is in the same collection and institution.
            if (minimalAsset.parent_guid() != null) {
                AssetFull parent = assetService.getFullAsset(minimalAsset.parent_guid());
                String parentRemotePath = getRemotePath(new MinimalAsset(parent.asset_guid, parent.parent_guid, parent.institution, parent.collection));
                logger.info("Initialising parent folder, remote path is {}", parentRemotePath);
                try {
                    if (!exists(parentRemotePath,true)) {
                        logger.info("Remote parent path {} didnt exist ", parentRemotePath);
                        throw new RuntimeException();
//                        return;
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                List<String> parentFileNames = listAllFiles(parentRemotePath);
                downloadFiles(parentFileNames, sharePath + "/parent", parent.asset_guid);
            } else {
                throw new RuntimeException("parent iz nullz");
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
