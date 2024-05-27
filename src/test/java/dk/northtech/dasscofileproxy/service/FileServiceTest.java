package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.repository.FileRepository;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.*;
import java.nio.channels.FileChannel;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;


@SpringBootTest
@Testcontainers
@DirtiesContext
@Disabled
public class FileServiceTest {
    @Inject
    FileService fileService;

    @Container
    static GenericContainer postgreSQL = new GenericContainer(DockerImageName.parse("apache/age:v1.1.0"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "dassco_file_proxy")
            .withEnv("POSTGRES_USER", "dassco_file_proxy")
            .withEnv("POSTGRES_PASSWORD", "dassco_file_proxy");

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.jdbcUrl", () -> "jdbc:postgresql://localhost:" + postgreSQL.getFirstMappedPort() + "/dassco_file_proxy");
    }

    //    @Test
//    @Disabled
//    public void test () {
//        assertThat(fileService.createShareFolder(16L)).isNull();
//    }
    @Inject
    HttpShareService httpShareService;

    @Test
    public void testUpload() {
        SharedAsset azzet1 = new SharedAsset(null, null, "testUpload", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/testUpload/", "localhost:8080", AccessType.WRITE, Instant.now(), 10, false, 0, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        fileService.createShareFolder(new MinimalAsset("testUpload", "p", "i1", "c1"));
//        httpShareService.createHttpShare(new CreationObj(Arrays.asList(new MinimalAsset("testUpload", "testUploadP", "i1", "c1")),Arrays.asList("Bazviolas"), 10), new User());
        FileUploadResult upload = fileService.upload(new ByteArrayInputStream("Et håndtag i form af en springende hjort".getBytes()), 1475383058, new FileUploadData("testUpload", "i1", "c1", "/folder/Bibelen 2 Del 1 - Det Moderne testamente.txt", 1));
        FileUploadResult upload2 = fileService.upload(new ByteArrayInputStream("Et håndtag i form af en springende hjort".getBytes()), 1475383058, new FileUploadData("testUpload", "i1", "c1", "/folder/Bibelen 2 Del 2 - Genkomst.txt", 9));
        List<DasscoFile> testUpload = fileService.listFilesByAssetGuid("testUpload");
        assertThat(testUpload.size()).isEqualTo(2);
    }

    @Test
    public void testDeleteFile() {
        SharedAsset azzet1 = new SharedAsset(null, null, "testDeleteFile", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/testDeleteFile/", "localhost:8080", AccessType.WRITE, Instant.now(), 10, false, 0, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        fileService.createShareFolder(new MinimalAsset("testDeleteFile", "p", "i1", "c1"));
//        httpShareService.createHttpShare(new CreationObj(Arrays.asList(new MinimalAsset("testUpload", "testUploadP", "i1", "c1")),Arrays.asList("Bazviolas"), 10), new User());
        FileUploadResult upload = fileService.upload(new ByteArrayInputStream("Et håndtag i form af en springende hjort".getBytes()), 1475383058, new FileUploadData("testDeleteFile", "i1", "c1", "/folder/Bibelen 2 Del 1 - Det Moderne testamente.txt", 1));
        FileUploadResult upload2 = fileService.upload(new ByteArrayInputStream("Et håndtag i form af en springende hjort".getBytes()), 1475383058, new FileUploadData("testDeleteFile", "i1", "c1", "/folder/Bibelen 2 Del 2 - Genkomst.txt", 9));
        fileService.deleteFile(new FileUploadData("testDeleteFile", "i1", "c1", "/folder/Bibelen 2 Del 1 - Det Moderne testamente.txt", 9));
        List<DasscoFile> testUpload = fileService.listFilesByAssetGuid("testDeleteFile");
        assertThat(testUpload.size()).isEqualTo(2);
        long count = testUpload.stream().filter(DasscoFile::deleteAfterSync).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    public void testDeleteFolderFile() {
        SharedAsset azzet1 = new SharedAsset(null, null, "testDeleteFolderFile", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/testDeleteFolderFile/", "localhost:8080", AccessType.WRITE, Instant.now(), 10, false, 0, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        fileService.createShareFolder(new MinimalAsset("testDeleteFolderFile", "p", "i1", "c1"));
//        httpShareService.createHttpShare(new CreationObj(Arrays.asList(new MinimalAsset("testUpload", "testUploadP", "i1", "c1")),Arrays.asList("Bazviolas"), 10), new User());
        FileUploadResult upload = fileService.upload(new ByteArrayInputStream("Et håndtag i form af en springende hjort".getBytes()), 1475383058, new FileUploadData("testDeleteFolderFile", "i1", "c1", "/folder/Bibelen 2 Del 1 - Det Moderne testamente.txt", 1));
        FileUploadResult upload2 = fileService.upload(new ByteArrayInputStream("Et håndtag i form af en springende hjort".getBytes()), 1475383058, new FileUploadData("testDeleteFolderFile", "i1", "c1", "/folder/Bibelen 2 Del 2 - Genkomst.txt", 9));
        fileService.deleteFile(new FileUploadData("testDeleteFolderFile", "i1", "c1", null, 9));
        List<DasscoFile> testUpload = fileService.listFilesByAssetGuid("testDeleteFolderFile");
        assertThat(testUpload.size()).isEqualTo(2);
        long count = testUpload.stream().filter(DasscoFile::deleteAfterSync).count();
        assertThat(count).isEqualTo(2);
    }

    @Test
    public void testUploadOverwrite() {
        SharedAsset azzet1 = new SharedAsset(null, null, "testUploadOverwrite", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/testUploadOverwrite/", "localhost:8080", AccessType.WRITE, Instant.now(), 10, false, 0, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        fileService.createShareFolder(new MinimalAsset("testUploadOverwrite", "testUploadOverwriteP", "i1", "c1"));
//        httpShareService.createHttpShare(new CreationObj(Arrays.asList(new MinimalAsset("testUpload", "testUploadP", "i1", "c1")),Arrays.asList("Bazviolas"), 10), new User());
        FileUploadResult upload = fileService.upload(new ByteArrayInputStream("Et håndtag i form af en springende hjort".getBytes()), 1475383058, new FileUploadData("testUploadOverwrite", "i1", "c1", "/folder/Bibelen 2 Del 1 - Det Moderne testamente.txt", 1));
        FileUploadResult upload2 = fileService.upload(new ByteArrayInputStream("Et håndtag i form af en springende hjort!".getBytes()), 1837465108, new FileUploadData("testUploadOverwrite", "i1", "c1", "/folder/Bibelen 2 Del 1 - Det Moderne testamente.txt", 9));
        System.out.println(upload.actual_crc());
        System.out.println(upload2.actual_crc());
        List<DasscoFile> testUpload = fileService.listFilesByAssetGuid("testUploadOverwrite");
        assertThat(testUpload.size()).isEqualTo(2);
        Optional<DasscoFile> toBeDeleted = testUpload.stream().filter(DasscoFile::deleteAfterSync).findFirst();
        assertThat(toBeDeleted.isPresent()).isTrue();
        assertThat(toBeDeleted.get().crc()).isEqualTo(1475383058);
        Optional<DasscoFile> newFile = testUpload.stream().filter(x -> !x.deleteAfterSync()).findFirst();
        assertThat(newFile.isPresent()).isTrue();
        assertThat(newFile.get().crc()).isEqualTo(1837465108);
    }

    @Test
    public void testUploadNotEnoughSpaceError() {
        SharedAsset azzet1 = new SharedAsset(null, null, "testUploadNotEnoughSpaceError", Instant.now());
        UserAccess userAccess = new UserAccess(null, null, "Bazviola", "token", Instant.now());
        Directory directory = new Directory(null, "/i1/c1/testUploadNotEnoughSpaceError/", "localhost:8080", AccessType.WRITE, Instant.now(), 10, false, 0, Arrays.asList(azzet1), Arrays.asList(userAccess));
        Directory directory1 = httpShareService.createDirectory(directory);
        fileService.createShareFolder(new MinimalAsset("testUploadNotEnoughSpaceError", "testUploadNotEnoughSpaceErrorP", "i1", "c1"));
//        httpShareService.createHttpShare(new CreationObj(Arrays.asList(new MinimalAsset("testUpload", "testUploadP", "i1", "c1")),Arrays.asList("Bazviolas"), 10), new User());
        FileUploadResult upload = fileService.upload(new ByteArrayInputStream("Et håndtag i form af en springende hjort".getBytes()), 1475383058, new FileUploadData("testUploadNotEnoughSpaceError", "i1", "c1", "/folder/The Kosst Amojan.txt", 1));
        IllegalArgumentException illegalArgumentException =
                assertThrows(IllegalArgumentException.class
                        , () -> fileService.upload(
                                new ByteArrayInputStream("Tezttezttezt".getBytes())
                                , 139372
                                , new FileUploadData("testUploadNotEnoughSpaceError", "i1", "c1", "/The.Navidson.Record.1080p.BluRay.x264.AC3.erdatv.mp4", 10)));
        assertThat(illegalArgumentException.getMessage()).isEqualTo("Total size of asset files exceeds allocated disk space");
        List<DasscoFile> testUpload = fileService.listFilesByAssetGuid("testUploadNotEnoughSpaceError");
        assertThat(testUpload.size()).isEqualTo(1);
    }
    @Test
    public void testListFilesByGuid() {
        fileService.jdbi.withHandle(h -> {
            FileRepository attach = h.attach(FileRepository.class);
            attach.insertFile(new DasscoFile(null, "a1", "/file.jpg", 13245L, 123, FileSyncStatus.NEW_FILE));
            attach.insertFile(new DasscoFile(null, "a2", "/test/tezt.txt", 13245L, 123, FileSyncStatus.NEW_FILE));
            attach.insertFile(new DasscoFile(null, "a2", "/test/tezt2.txt", 10000L, 12332, FileSyncStatus.NEW_FILE));
            return h;
        }).close();
        List<DasscoFile> result = fileService.listFilesByAssetGuid("a1");
        assertThat(result.size()).isEqualTo(1);
        DasscoFile dasscoFile = result.get(0);
        assertThat(dasscoFile.assetGuid()).isEqualTo("a1");
        assertThat(dasscoFile.path()).isEqualTo("/file.jpg");
        assertThat(dasscoFile.sizeBytes()).isEqualTo(13245L);
        assertThat(dasscoFile.crc()).isEqualTo(123);
        assertThat(dasscoFile.fileId()).isNotNull();
        assertThat(dasscoFile.fileId()).isGreaterThan(0);
        fileService.jdbi.withHandle(h -> {
            FileRepository attach = h.attach(FileRepository.class);
            attach.deleteFilesByAssetGuid("a1");
            return h;
        }).close();
        List<DasscoFile> res2 = fileService.listFilesByAssetGuid("a1");
        assertThat(res2).hasSize(0);
        List<DasscoFile> res3 = fileService.listFilesByAssetGuid("a2");
        assertThat(res3).hasSize(2);
        fileService.jdbi.withHandle(h -> {
            FileRepository attach = h.attach(FileRepository.class);
            assertThat(attach.getTotalAllocatedByAsset("a2")).isEqualTo(23245L);
            return h;
        }).close();
    }

//    @Test
//    public void tezt() {
//        File file = new File("C:/Users/Thomas/Documents/dassco-file-proxy/target/The.Navidson.Record.1080p.BluRay.x264.AC3.erdatv.mp4");
//        try {
//            FileOutputStream fileOutputStream = new FileOutputStream(file);
//            for(int i = 0 ; i < 100000000 ; i++) {
//                fileOutputStream.write(UUID.randomUUID().toString().getBytes());
//        }
//        } catch (FileNotFoundException e) {
//            throw new RuntimeException(e);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//    }

}
