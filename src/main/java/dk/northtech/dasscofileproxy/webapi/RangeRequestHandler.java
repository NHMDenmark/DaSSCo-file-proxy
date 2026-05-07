package dk.northtech.dasscofileproxy.webapi;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Optional;

/**
 * Utility class for handling HTTP Range requests for file downloads.
 * Supports resumable downloads with partial content (HTTP 206) responses.
 */
public class RangeRequestHandler {

    /**
     * Result of parsing a Range header.
     */
    public record RangeInfo(long start, long end, long contentLength) {
    }

    /**
     * Configuration for building a file response.
     */
    public static class FileResponseConfig {
        private final File file;
        private final String filename;
        private final String contentType;
        private String rangeHeader;
        private Runnable onComplete;
        private boolean includeContentDisposition = true;
        private boolean runOnCompleteForAnyRange = false;

        public FileResponseConfig(File file, String filename, String contentType) {
            this.file = file;
            this.filename = filename;
            this.contentType = contentType;
        }

        public FileResponseConfig withRangeHeader(String rangeHeader) {
            this.rangeHeader = rangeHeader;
            return this;
        }

        /**
         * Sets a callback to be executed when the file transfer completes (reaches end of file).
         * Useful for cleanup operations like invalidating tickets.
         */
        public FileResponseConfig onComplete(Runnable onComplete) {
            this.onComplete = onComplete;
            return this;
        }

        public FileResponseConfig withoutContentDisposition() {
            this.includeContentDisposition = false;
            return this;
        }

        public FileResponseConfig cleanupAfterAnyRange() {
            this.runOnCompleteForAnyRange = true;
            return this;
        }

        public File getFile() {
            return file;
        }

        public String getFilename() {
            return filename;
        }

        public String getContentType() {
            return contentType;
        }

        public String getRangeHeader() {
            return rangeHeader;
        }

        public Runnable getOnComplete() {
            return onComplete;
        }

        public boolean includeContentDisposition() {
            return includeContentDisposition;
        }

        public boolean runOnCompleteForAnyRange() {
            return runOnCompleteForAnyRange;
        }
    }

    /**
     * Parses the Range header and validates it against the file length.
     *
     * @param rangeHeader the Range header value (e.g., "bytes=0-1023")
     * @param fileLength  the total length of the file
     * @return Optional containing RangeInfo if valid, empty if invalid
     */
    public static Optional<RangeInfo> parseRangeHeader(String rangeHeader, long fileLength) {
        if (rangeHeader == null || rangeHeader.isEmpty()) {
            return Optional.empty();
        }

        if (!rangeHeader.startsWith("bytes=")) {
            return Optional.empty();
        }

        String rangeValue = rangeHeader.substring(6); // Remove "bytes="
        String[] rangeParts = rangeValue.split("-");

        long start;
        long end;

        try {
            start = Long.parseLong(rangeParts[0]);
            if (rangeParts.length > 1 && !rangeParts[1].isEmpty()) {
                end = Long.parseLong(rangeParts[1]);
            } else {
                end = fileLength - 1;
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        // Validate range
        if (start < 0 || start >= fileLength || end < start || end >= fileLength) {
            return Optional.empty();
        }

        return Optional.of(new RangeInfo(start, end, end - start + 1));
    }

    /**
     * Creates a Response for Range Not Satisfiable (HTTP 416).
     *
     * @param fileLength the total length of the file
     * @return Response with status 416
     */
    public static Response rangeNotSatisfiable(long fileLength) {
        return Response.status(416)
                .header("Content-Range", "bytes */" + fileLength)
                .build();
    }

    /**
     * Creates a streaming output for reading a full file.
     *
     * @param file       the file to stream
     * @param onComplete optional callback when streaming completes
     * @return StreamingOutput for the full file
     */
    public static StreamingOutput createFullFileStream(File file, Runnable onComplete) {
        return output -> {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = raf.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                output.flush();
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        };
    }

    /**
     * Creates a streaming output for reading a partial file (range request).
     *
     * @param file          the file to stream
     * @param rangeInfo     the range information
     * @param fileLength    the total file length
     * @param onComplete    optional callback when streaming completes (only called if range ends at file end)
     * @return StreamingOutput for the partial file
     */
    public static StreamingOutput createRangeStream(File file, RangeInfo rangeInfo, long fileLength, Runnable onComplete) {
        return createRangeStream(file, rangeInfo, fileLength, onComplete, false);
    }

    public static StreamingOutput createRangeStream(File file, RangeInfo rangeInfo, long fileLength, Runnable onComplete, boolean runOnCompleteForAnyRange) {
        return output -> {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(rangeInfo.start());
                byte[] buffer = new byte[8192];
                long remaining = rangeInfo.contentLength();
                int bytesRead;

                while (remaining > 0
                        && (bytesRead = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                    output.write(buffer, 0, bytesRead);
                    remaining -= bytesRead;
                }
                output.flush();
            } finally {
                if (onComplete != null && (runOnCompleteForAnyRange || rangeInfo.end() == fileLength - 1)) {
                    onComplete.run();
                }
            }
        };
    }

    /**
     * Builds a complete Response for a file download with optional Range header support.
     * This is the main entry point for handling file downloads with range support.
     *
     * @param config the file response configuration
     * @return Response with either full file (200) or partial content (206)
     */
    public static Response buildFileResponse(FileResponseConfig config) {
        File file = config.getFile();
        long fileLength = file.length();
        String contentDisposition = "attachment; filename=\"" + config.getFilename() + "\"";

        // If no Range header, return the entire file
        if (config.getRangeHeader() == null || config.getRangeHeader().isEmpty()) {
            StreamingOutput streamingOutput = createFullFileStream(file, config.getOnComplete());

            Response.ResponseBuilder responseBuilder = Response.ok(streamingOutput)
                    .header("Content-Type", config.getContentType())
                    .header("Content-Length", fileLength)
                    .header("Accept-Ranges", "bytes");
            if (config.includeContentDisposition()) {
                responseBuilder.header("Content-Disposition", contentDisposition);
            }
            return responseBuilder.build();
        }

        // Parse and validate Range header
        Optional<RangeInfo> rangeInfoOpt = parseRangeHeader(config.getRangeHeader(), fileLength);
        if (rangeInfoOpt.isEmpty()) {
            return rangeNotSatisfiable(fileLength);
        }

        RangeInfo rangeInfo = rangeInfoOpt.get();
        StreamingOutput streamingOutput = createRangeStream(file, rangeInfo, fileLength, config.getOnComplete(), config.runOnCompleteForAnyRange());

        Response.ResponseBuilder responseBuilder = Response.status(206)
                .entity(streamingOutput)
                .header("Content-Type", config.getContentType())
                .header("Content-Length", rangeInfo.contentLength())
                .header("Content-Range", "bytes " + rangeInfo.start() + "-" + rangeInfo.end() + "/" + fileLength)
                .header("Accept-Ranges", "bytes");
        if (config.includeContentDisposition()) {
            responseBuilder.header("Content-Disposition", contentDisposition);
        }
        return responseBuilder.build();
    }
}
