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
        private Runnable onStart;
        private Runnable onFinished;
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

        public FileResponseConfig onStart(Runnable onStart) {
            this.onStart = onStart;
            return this;
        }

        public FileResponseConfig onFinished(Runnable onFinished) {
            this.onFinished = onFinished;
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

        public Runnable getOnStart() {
            return onStart;
        }

        public Runnable getOnFinished() {
            return onFinished;
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
        if (rangeHeader == null || rangeHeader.isBlank() || fileLength <= 0) {
            return Optional.empty();
        }

        String trimmedHeader = rangeHeader.trim();
        if (!trimmedHeader.regionMatches(true, 0, "bytes=", 0, 6)) {
            return Optional.empty();
        }

        String rangeValue = trimmedHeader.substring(6).trim();
        if (rangeValue.isEmpty() || rangeValue.contains(",")) {
            return Optional.empty();
        }

        try {
            if (rangeValue.startsWith("-")) {
                String suffixPart = rangeValue.substring(1).trim();
                if (suffixPart.isEmpty() || suffixPart.contains("-")) {
                    return Optional.empty();
                }

                long suffixLength = Long.parseLong(suffixPart);
                if (suffixLength <= 0) {
                    return Optional.empty();
                }

                long contentLength = Math.min(suffixLength, fileLength);
                long start = fileLength - contentLength;
                long end = fileLength - 1;
                return Optional.of(new RangeInfo(start, end, contentLength));
            }

            int dashIndex = rangeValue.indexOf('-');
            if (dashIndex < 0 || rangeValue.indexOf('-', dashIndex + 1) >= 0) {
                return Optional.empty();
            }

            String startPart = rangeValue.substring(0, dashIndex).trim();
            String endPart = rangeValue.substring(dashIndex + 1).trim();
            if (startPart.isEmpty()) {
                return Optional.empty();
            }

            long start = Long.parseLong(startPart);
            long end = endPart.isEmpty() ? fileLength - 1 : Long.parseLong(endPart);

            if (start < 0 || start >= fileLength || end < start) {
                return Optional.empty();
            }

            end = Math.min(end, fileLength - 1);
            return Optional.of(new RangeInfo(start, end, end - start + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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
        return createFullFileStream(file, onComplete, null, null);
    }

    public static StreamingOutput createFullFileStream(File file, Runnable onComplete, Runnable onStart, Runnable onFinished) {
        return output -> {
            boolean completed = false;
            boolean started = false;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                if (onStart != null) {
                    onStart.run();
                }
                started = true;
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = raf.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                output.flush();
                completed = true;
            } finally {
                if (completed && onComplete != null) {
                    onComplete.run();
                }
                if (started && onFinished != null) {
                    onFinished.run();
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
        return createRangeStream(file, rangeInfo, fileLength, onComplete, runOnCompleteForAnyRange, null, null);
    }

    public static StreamingOutput createRangeStream(File file, RangeInfo rangeInfo, long fileLength, Runnable onComplete, boolean runOnCompleteForAnyRange, Runnable onStart, Runnable onFinished) {
        return output -> {
            boolean completed = false;
            boolean started = false;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                if (onStart != null) {
                    onStart.run();
                }
                started = true;
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
                completed = remaining == 0;
            } finally {
                if (completed && onComplete != null && (runOnCompleteForAnyRange || rangeInfo.end() == fileLength - 1)) {
                    onComplete.run();
                }
                if (started && onFinished != null) {
                    onFinished.run();
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
            StreamingOutput streamingOutput = createFullFileStream(file, config.getOnComplete(), config.getOnStart(), config.getOnFinished());

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
        StreamingOutput streamingOutput = createRangeStream(file, rangeInfo, fileLength, config.getOnComplete(), config.runOnCompleteForAnyRange(), config.getOnStart(), config.getOnFinished());

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
