package dk.northtech.dasscofileproxy.webapi;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;

class RangeRequestHandlerTest {

    private File testFile;
    private byte[] testContent;
    
    // 1 MB test file for realistic range request testing
    private static final int TEST_FILE_SIZE = 1024 * 1024;
    private static final long RANDOM_SEED = 42L;

    @BeforeEach
    void setUp() throws IOException {
        testFile = File.createTempFile("range-test", ".bin");
        
        // Generate deterministic random content for reproducible tests
        Random random = new Random(RANDOM_SEED);
        testContent = new byte[TEST_FILE_SIZE];
        random.nextBytes(testContent);
        
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            fos.write(testContent);
        }
    }

    @AfterEach
    void tearDown() {
        if (testFile != null && testFile.exists()) {
            testFile.delete();
        }
    }

    // Tests for parseRangeHeader

    @Test
    void parseRangeHeader_nullHeader_returnsEmpty() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader(null, 100);
        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void parseRangeHeader_emptyHeader_returnsEmpty() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("", 100);
        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void parseRangeHeader_invalidPrefix_returnsEmpty() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("invalid=0-10", 100);
        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void parseRangeHeader_validFullRange_returnsRangeInfo() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("bytes=0-10", 100);
        assertThat(result.isPresent()).isTrue();
        assertThat(result.get().start()).isEqualTo(0);
        assertThat(result.get().end()).isEqualTo(10);
        assertThat(result.get().contentLength()).isEqualTo(11);
    }

    @Test
    void parseRangeHeader_openEndedRange_returnsRangeToEndOfFile() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("bytes=50-", 100);
        assertThat(result.isPresent()).isTrue();
        assertThat(result.get().start()).isEqualTo(50);
        assertThat(result.get().end()).isEqualTo(99);
        assertThat(result.get().contentLength()).isEqualTo(50);
    }

    @Test
    void parseRangeHeader_startBeyondFileLength_returnsEmpty() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("bytes=100-150", 100);
        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void parseRangeHeader_endBeyondFileLength_returnsEmpty() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("bytes=0-150", 100);
        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void parseRangeHeader_negativeStart_returnsEmpty() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("bytes=-10-50", 100);
        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void parseRangeHeader_endBeforeStart_returnsEmpty() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("bytes=50-10", 100);
        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void parseRangeHeader_nonNumericValues_returnsEmpty() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("bytes=abc-def", 100);
        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void parseRangeHeader_entireFile_returnsCorrectRange() {
        Optional<RangeRequestHandler.RangeInfo> result = RangeRequestHandler.parseRangeHeader("bytes=0-99", 100);
        assertThat(result.isPresent()).isTrue();
        assertThat(result.get().start()).isEqualTo(0);
        assertThat(result.get().end()).isEqualTo(99);
        assertThat(result.get().contentLength()).isEqualTo(100);
    }

    // Tests for rangeNotSatisfiable

    @Test
    void rangeNotSatisfiable_returns416WithContentRange() {
        Response response = RangeRequestHandler.rangeNotSatisfiable(1000);
        assertThat(response.getStatus()).isEqualTo(416);
        assertThat(response.getHeaderString("Content-Range")).isEqualTo("bytes */1000");
    }

    // Tests for createFullFileStream

    @Test
    void createFullFileStream_streamsEntireFile() throws IOException {
        StreamingOutput stream = RangeRequestHandler.createFullFileStream(testFile, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        stream.write(output);
        assertThat(output.toByteArray()).isEqualTo(testContent);
    }

    @Test
    void createFullFileStream_callsOnCompleteCallback() throws IOException {
        boolean[] callbackCalled = {false};
        StreamingOutput stream = RangeRequestHandler.createFullFileStream(testFile, () -> callbackCalled[0] = true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        stream.write(output);
        assertThat(callbackCalled[0]).isTrue();
    }

    // Tests for createRangeStream

    @Test
    void createRangeStream_streamsPartialContent() throws IOException {
        // Request first 1KB of the file
        int rangeLength = 1024;
        RangeRequestHandler.RangeInfo rangeInfo = new RangeRequestHandler.RangeInfo(0, rangeLength - 1, rangeLength);
        StreamingOutput stream = RangeRequestHandler.createRangeStream(testFile, rangeInfo, testFile.length(), null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        stream.write(output);
        
        byte[] expected = Arrays.copyOfRange(testContent, 0, rangeLength);
        assertThat(output.toByteArray()).isEqualTo(expected);
    }

    @Test
    void createRangeStream_streamsMiddleContent() throws IOException {
        // Request 64KB chunk from the middle of the file (starting at 512KB)
        int start = 512 * 1024;
        int length = 64 * 1024;
        int end = start + length - 1;
        RangeRequestHandler.RangeInfo rangeInfo = new RangeRequestHandler.RangeInfo(start, end, length);
        StreamingOutput stream = RangeRequestHandler.createRangeStream(testFile, rangeInfo, testFile.length(), null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        stream.write(output);
        
        byte[] expected = Arrays.copyOfRange(testContent, start, start + length);
        assertThat(output.toByteArray()).isEqualTo(expected);
    }

    @Test
    void createRangeStream_callsOnCompleteWhenReachingEndOfFile() throws IOException {
        boolean[] callbackCalled = {false};
        long fileLength = testFile.length();
        RangeRequestHandler.RangeInfo rangeInfo = new RangeRequestHandler.RangeInfo(0, fileLength - 1, fileLength);
        StreamingOutput stream = RangeRequestHandler.createRangeStream(testFile, rangeInfo, fileLength, () -> callbackCalled[0] = true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        stream.write(output);
        assertThat(callbackCalled[0]).isTrue();
    }

    @Test
    void createRangeStream_doesNotCallOnCompleteWhenNotReachingEndOfFile() throws IOException {
        boolean[] callbackCalled = {false};
        // Request only first 100KB, not reaching end of 1MB file
        int rangeLength = 100 * 1024;
        RangeRequestHandler.RangeInfo rangeInfo = new RangeRequestHandler.RangeInfo(0, rangeLength - 1, rangeLength);
        StreamingOutput stream = RangeRequestHandler.createRangeStream(testFile, rangeInfo, testFile.length(), () -> callbackCalled[0] = true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        stream.write(output);
        assertThat(callbackCalled[0]).isFalse();
    }

    // Tests for buildFileResponse

    @Test
    void buildFileResponse_noRangeHeader_returns200WithFullFile() {
        RangeRequestHandler.FileResponseConfig config = new RangeRequestHandler.FileResponseConfig(testFile, "test.txt", "text/plain");
        try (Response response = RangeRequestHandler.buildFileResponse(config)) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeaderString("Content-Disposition")).isEqualTo("attachment; filename=\"test.txt\"");
            assertThat(response.getHeaderString("Content-Type")).isEqualTo("text/plain");
            assertThat(response.getHeaderString("Content-Length")).isEqualTo(String.valueOf(testFile.length()));
            assertThat(response.getHeaderString("Accept-Ranges")).isEqualTo("bytes");
        }
    }

    @Test
    void buildFileResponse_emptyRangeHeader_returns200WithFullFile() {
        RangeRequestHandler.FileResponseConfig config = new RangeRequestHandler.FileResponseConfig(testFile, "test.txt", "text/plain")
                .withRangeHeader("");
        try (Response response = RangeRequestHandler.buildFileResponse(config)) {
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void buildFileResponse_validRangeHeader_returns206WithPartialContent() {
        // Request first 256KB
        int rangeEnd = 256 * 1024 - 1;
        RangeRequestHandler.FileResponseConfig config = new RangeRequestHandler.FileResponseConfig(testFile, "test.bin", "application/octet-stream")
                .withRangeHeader("bytes=0-" + rangeEnd);
        try (Response response = RangeRequestHandler.buildFileResponse(config)) {
            assertThat(response.getStatus()).isEqualTo(206);
            assertThat(response.getHeaderString("Content-Disposition")).isEqualTo("attachment; filename=\"test.bin\"");
            assertThat(response.getHeaderString("Content-Type")).isEqualTo("application/octet-stream");
            assertThat(response.getHeaderString("Content-Length")).isEqualTo(String.valueOf(256 * 1024));
            assertThat(response.getHeaderString("Content-Range")).isEqualTo("bytes 0-" + rangeEnd + "/" + testFile.length());
            assertThat(response.getHeaderString("Accept-Ranges")).isEqualTo("bytes");
        }
    }

    @Test
    void buildFileResponse_invalidRangeHeader_returns416() {
        // Request range beyond file size (file is 1MB, requesting 2MB-3MB)
        RangeRequestHandler.FileResponseConfig config = new RangeRequestHandler.FileResponseConfig(testFile, "test.bin", "application/octet-stream")
                .withRangeHeader("bytes=2097152-3145727");
        try (Response response = RangeRequestHandler.buildFileResponse(config)) {
            assertThat(response.getStatus()).isEqualTo(416);
            assertThat(response.getHeaderString("Content-Range")).isEqualTo("bytes */" + testFile.length());
        }
    }

    @Test
    void buildFileResponse_openEndedRange_returns206WithCorrectRange() {
        // Request from 768KB to end of file (last 256KB of 1MB file)
        int startOffset = 768 * 1024;
        RangeRequestHandler.FileResponseConfig config = new RangeRequestHandler.FileResponseConfig(testFile, "test.bin", "application/octet-stream")
                .withRangeHeader("bytes=" + startOffset + "-");
        try (Response response = RangeRequestHandler.buildFileResponse(config)) {
            assertThat(response.getStatus()).isEqualTo(206);
            long expectedLength = testFile.length() - startOffset;
            assertThat(response.getHeaderString("Content-Length")).isEqualTo(String.valueOf(expectedLength));
            assertThat(response.getHeaderString("Content-Range")).isEqualTo("bytes " + startOffset + "-" + (testFile.length() - 1) + "/" + testFile.length());
        }
    }

    // Tests for FileResponseConfig

    @Test
    void fileResponseConfig_gettersReturnCorrectValues() {
        RangeRequestHandler.FileResponseConfig config = new RangeRequestHandler.FileResponseConfig(testFile, "test.txt", "text/plain")
                .withRangeHeader("bytes=0-10");

        assertThat(config.getFile()).isEqualTo(testFile);
        assertThat(config.getFilename()).isEqualTo("test.txt");
        assertThat(config.getContentType()).isEqualTo("text/plain");
        assertThat(config.getRangeHeader()).isEqualTo("bytes=0-10");
    }

    @Test
    void fileResponseConfig_onCompleteCallback() {
        boolean[] callbackCalled = {false};
        RangeRequestHandler.FileResponseConfig config = new RangeRequestHandler.FileResponseConfig(testFile, "test.txt", "text/plain")
                .onComplete(() -> callbackCalled[0] = true);

        assertThat(config.getOnComplete()).isNotNull();
        config.getOnComplete().run();
        assertThat(callbackCalled[0]).isTrue();
    }
}
