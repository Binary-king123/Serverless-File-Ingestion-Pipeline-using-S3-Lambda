package com.s3fileprocesslambdalayer;

import com.s3fileprocesslambdalayer.SharedLambdaLayer;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;

import software.amazon.awssdk.services.s3.S3Client; // Import AWS SDK v2 S3Client
import software.amazon.awssdk.services.s3.model.S3Exception; // For S3-specific errors
import software.amazon.awssdk.services.s3.model.NoSuchKeyException; // For specific S3 'object not found' error

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets; // For specifying UTF-8 encoding
import java.nio.file.Files; // For Files.newBufferedReader
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class CSVFileProcessor implements RequestHandler<S3Event, String> {

    private static final Logger logger = Logger.getLogger(CSVFileProcessor.class.getName());

    // Instantiate S3Client once per container lifecycle (cold start) for efficiency
    private static final S3Client s3Client = S3Client.builder().build();

    // Environment variable for the S3 bucket name, initialized once per container
    private final String bucketName;

    public CSVFileProcessor() {
        this.bucketName = System.getenv("BUCKET_NAME");
        if (this.bucketName == null || this.bucketName.isEmpty()) {
            logger.severe("BUCKET_NAME environment variable is not set. This function may not operate correctly.");
            // Consider throwing a RuntimeException here to fail fast if this variable is critical.
        }
        logger.info("CSVFileProcessor initialized. Target Bucket: " + bucketName);
    }

    @Override
    public String handleRequest(S3Event event, Context context) {
        logger.info("Received S3 event for CSV processing. Request ID: " + context.getAwsRequestId());
        long startTime = System.currentTimeMillis();

        if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
            logger.warning("No records found in the S3 event. Exiting.");
            return "No records to process.";
        }

        List<String> failedFiles = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>(); // To track explicitly skipped files

        for (S3EventNotificationRecord record : event.getRecords()) {
            String rawKey = record.getS3().getObject().getKey();
            String s3Bucket = record.getS3().getBucket().getName();
            String s3Key = null; // Initialize s3Key here

            try {
                // Decode the S3 key, as it can contain URL-encoded characters (e.g., spaces as %20)
                s3Key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                logger.info(String.format("Processing record for S3 object: s3://%s/%s (decoded: %s)", s3Bucket, rawKey, s3Key));

                // Defensive check: Ensure the event is for the configured bucket (though SAM handles this well)
                if (!s3Bucket.equals(this.bucketName)) {
                    logger.warning(String.format("Event for bucket '%s' but expected '%s'. Skipping file: %s", s3Bucket, this.bucketName, s3Key));
                    skippedFiles.add(s3Key);
                    continue;
                }

                // Defensive check: Ensure it's a CSV file (though S3 event filter handles this too)
                if (!s3Key.toLowerCase().endsWith(".csv")) {
                    logger.warning("Skipped non-CSV file based on suffix check: " + s3Key);
                    skippedFiles.add(s3Key);
                    continue;
                }

                Path localFilePath = null;
                try {
                    // Download the file using the SharedLambdaLayer's static method
                    // Using downloadFileAsPath as it returns a Path and throws checked exceptions
                    File downloadedFile = SharedLambdaLayer.downloadFile(s3Client, s3Bucket, s3Key);
                    logger.info("Downloaded file location: " + downloadedFile.getAbsolutePath());
                    localFilePath = downloadedFile.toPath();
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
                    // --- CSV Specific Processing Logic ---
                    processCsvFile(localFilePath);
                    // --- End CSV Specific Processing Logic ---

                } catch (NoSuchKeyException e) {
                    logger.severe(String.format("File not found on S3 for key %s in bucket %s: %s", s3Key, s3Bucket, e.getMessage()));
                    failedFiles.add(s3Key);
                } catch (S3Exception e) {
                    logger.severe(String.format("S3 error during download for %s: %s (AWS Request ID: %s)", s3Key, e.getMessage(), e.requestId()));
                    failedFiles.add(s3Key);
                } catch (IOException e) {
                    logger.severe(String.format("I/O error during download or processing for %s: %s", s3Key, e.getMessage()));
                    failedFiles.add(s3Key);
                } finally {
                    // Crucial: Clean up the downloaded file from /tmp to avoid disk space issues
                    if (localFilePath != null) {
                        SharedLambdaLayer.cleanUpFile(localFilePath);
                    }
                }
            } catch (Exception e) { // Catch any unexpected errors during key decoding or initial checks
                logger.severe(String.format("Unhandled exception for raw key %s: %s", rawKey, e.getMessage()));
                failedFiles.add(s3Key != null ? s3Key : rawKey + " (decode_failed)");
            }
        }

        long endTime = System.currentTimeMillis();
        logger.info(String.format("CSV processing completed. Total time: %d ms", (endTime - startTime)));

        StringBuilder result = new StringBuilder("Processing Summary: ");
        if (failedFiles.isEmpty()) {
            result.append("All files processed successfully.");
        } else {
            result.append("Failed to process ").append(failedFiles.size()).append(" file(s): ").append(String.join(", ", failedFiles));
        }
        if (!skippedFiles.isEmpty()) {
            result.append(". Skipped ").append(skippedFiles.size()).append(" file(s): ").append(String.join(", ", skippedFiles));
        }

        return result.toString();
    }

    /**
     * Placeholder for actual CSV content processing.
     * Reads each line of the CSV file.
     *
     * @param filePath The local Path to the downloaded CSV file.
     * @throws IOException If an I/O error occurs during file reading.
     */
    private void processCsvFile(Path filePath) throws IOException {
        logger.info("Starting CSV content processing for: " + filePath.toString());

        // Use Files.newBufferedReader with explicit UTF-8 encoding for robust CSV parsing
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                // In a real-world scenario, you would parse this 'line' (e.g., using a CSV library),
                // validate the data, transform it, and then store it (e.g., in DynamoDB, RDS, SQS, Kinesis).
                logger.info("CSV Line " + lineNumber + ": " + line);

                // Add a log truncation limit to prevent excessive logging for very large files,
                // which can lead to CloudWatch costs or Lambda timeout issues just from logging.
                if (lineNumber >= 100) {
                    logger.info("Truncating CSV line logging after 100 lines for efficiency. Further processing continues silently.");
                    // In a real scenario, you'd process all lines, just not log them verbosely.
                    // If you stop here, make sure that's intended for your business logic.
                    // For demo, we can break to save log space.
                    // For actual processing, you'd remove this break unless you truly want to stop after 100 lines.
                    break;
                }
            }
        } // The BufferedReader is automatically closed by try-with-resources
        logger.info("Finished CSV content parsing for: " + filePath);
    }
}




