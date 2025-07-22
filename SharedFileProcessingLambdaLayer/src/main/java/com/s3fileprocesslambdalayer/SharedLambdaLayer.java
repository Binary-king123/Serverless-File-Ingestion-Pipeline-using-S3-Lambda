package com.s3fileprocesslambdalayer;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class SharedLambdaLayer {
    private static final Logger logger = Logger.getLogger(SharedLambdaLayer.class.getName());

    /**
     * Downloads a file from S3 to the /tmp directory and returns its local File reference.
     */
    public static File downloadFile(S3Client s3, String bucketName, String key) {
        try {
            String filePath = getTempDir() + "/" + Paths.get(key).getFileName();
            File localFile = new File(filePath);
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build(), localFile.toPath());
            logger.info("Downloaded to: " + filePath);
            return localFile;
        } catch (Exception e) {
            logger.severe("Error downloading file: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Downloads a file using stream and returns its Path. Useful for larger files.
     */
    public static Path downloadFileAsPath(S3Client s3, String bucketName, String key) throws IOException, S3Exception {
        Path localPath = Paths.get(getTempDir(), Paths.get(key).getFileName().toString());
        logger.info("Downloading via stream to: " + localPath);

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        try (ResponseInputStream<GetObjectResponse> s3Object = s3.getObject(request)) {
            Files.copy(s3Object, localPath);
            return localPath;
        }
    }

    /**
     * Deletes a single file in /tmp.
     */
    public static void cleanUpFile(Path localPath) {
        try {
            boolean deleted = Files.deleteIfExists(localPath);
            if (deleted) {
                logger.info("Deleted: " + localPath);
            }
        } catch (IOException e) {
            logger.warning("Cleanup failed for: " + localPath + ", reason: " + e.getMessage());
        }
    }

    /**
     * Cleans up all files in /tmp.
     */
    public static void cleanUpTempDirectory() {
        try (Stream<Path> paths = Files.list(Paths.get(getTempDir()))) {
            paths.forEach(file -> {
                try {
                    Files.delete(file);
                    logger.fine("Deleted from /tmp: " + file.getFileName());
                } catch (IOException e) {
                    logger.warning("Failed to delete: " + file.getFileName());
                }
            });
        } catch (IOException e) {
            logger.severe("Error cleaning up /tmp: " + e.getMessage());
        }

    }

    private static String getTempDir() {
        return System.getenv("TEMP_DIR") != null ? System.getenv("TEMP_DIR") : "/tmp";
    }
}