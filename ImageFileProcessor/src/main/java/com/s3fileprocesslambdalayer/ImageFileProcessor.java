package com.s3fileprocesslambdalayer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ImageFileProcessor implements RequestHandler<S3Event, String> {

    private static final Logger logger = Logger.getLogger(ImageFileProcessor.class.getName());
    private static final S3Client s3Client = S3Client.builder().build();
    private final String bucketName;

    public ImageFileProcessor() {
        this.bucketName = System.getenv("BUCKET_NAME");
        if (this.bucketName == null || this.bucketName.isEmpty()) {
            logger.severe("BUCKET_NAME environment variable is not set.");
        }
        logger.info("ImageFileProcessor initialized for bucket: " + this.bucketName);
    }

    @Override
    public String handleRequest(S3Event event, Context context) {
        logger.info("Received S3 event. Request ID: " + context.getAwsRequestId());
        long start = System.currentTimeMillis();

        if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
            return "No S3 event records found.";
        }

        List<String> failedFiles = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        final List<String> allowedSuffixes = Arrays.asList(".jpg", ".jpeg", ".png", ".gif");

        for (S3EventNotificationRecord record : event.getRecords()) {
            String rawKey = record.getS3().getObject().getKey();
            String s3Bucket = record.getS3().getBucket().getName();
            // IMPORTANT: Initialize s3Key with rawKey in case decoding fails
            String s3Key = rawKey;

            // <--- ADD START OF OUTER TRY BLOCK HERE
            try {
                // Decode the S3 key, as it can contain URL-encoded characters
                s3Key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                logger.info(String.format("Processing record for S3 object: s3://%s/%s (decoded: %s)", s3Bucket, rawKey, s3Key));

                // Defensive checks: Validate bucket and file type
                if (!s3Bucket.equals(this.bucketName)) {
                    logger.warning("Mismatched bucket. Expected: " + this.bucketName + ", got: " + s3Bucket);
                    skippedFiles.add(s3Key);
                    continue;
                }

                boolean isImage = allowedSuffixes.stream().anyMatch(s3Key.toLowerCase()::endsWith);
                if (!isImage) {
                    logger.warning("Unsupported image format: " + s3Key);
                    skippedFiles.add(s3Key);
                    continue;
                }

                Path localPath = null;
                try {
                    localPath = SharedLambdaLayer.downloadFileAsPath(s3Client, s3Bucket, s3Key);
                    // ✅ Log files in /tmp after download
                    File tmpDir = new File("/tmp");
                    String[] tmpFiles = tmpDir.list();
                    if (tmpFiles != null && tmpFiles.length > 0) {
                        logger.info("Files present in /tmp:");
                        for (String file : tmpFiles) {
                            logger.info(" - " + file);
                        }
                    } else {
                        logger.info("No files present in /tmp.");
                    }
                    processImageFile(localPath);

                } catch (NoSuchKeyException e) {
                    // Handle the specific case where the file was not found
                    logger.warning("File not found on S3: " + s3Key);
                    failedFiles.add(s3Key);
                } catch (S3Exception e) {
                    // Handle other S3-related errors (e.g., permissions, service issues)
                    logger.log(Level.SEVERE, "S3 error processing " + s3Key, e);
                    failedFiles.add(s3Key);
                } catch (IOException e) {
                    // Handle local I/O errors during download or processing
                    logger.log(Level.SEVERE, "I/O error processing " + s3Key, e);
                    failedFiles.add(s3Key);
                } finally {
                    if (localPath != null) {
                        SharedLambdaLayer.cleanUpFile(localPath);
                    }
                }
                // <--- ADD END OF TRY BLOCK HERE
            } catch (Exception e) {
                // This catch block handles any unexpected exceptions during initial processing
                // like a malformed URL-encoded key.
                logger.log(Level.SEVERE, "Unhandled exception processing record for raw key: " + rawKey, e);
                // Use the decoded key if available, otherwise the raw key
                failedFiles.add(s3Key);
            }
            // <--- ADD END OF CATCH BLOCK HERE
        }

        long duration = System.currentTimeMillis() - start;
        logger.info("Image processing completed in " + duration + " ms");

        StringBuilder result = new StringBuilder("Summary: ");
        if (failedFiles.isEmpty()) {
            result.append("All images processed successfully.");
        } else {
            result.append("Failed: ").append(String.join(", ", failedFiles));
        }
        if (!skippedFiles.isEmpty()) {
            result.append(" | Skipped: ").append(String.join(", ", skippedFiles));
        }
        return result.toString();
    }

    private void processImageFile(Path path) throws IOException {
        logger.info("Reading image from path: " + path);
        BufferedImage image = ImageIO.read(path.toFile());

        if (image != null) {
            logger.info("Image read successfully. Dimensions: " +
                    image.getWidth() + "x" + image.getHeight());
        } else {
            throw new IOException("Unsupported or corrupt image format.");
        }
    }
}