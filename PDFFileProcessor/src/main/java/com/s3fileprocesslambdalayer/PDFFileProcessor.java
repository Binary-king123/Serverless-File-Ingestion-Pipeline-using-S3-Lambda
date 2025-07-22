package com.s3fileprocesslambdalayer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class PDFFileProcessor implements RequestHandler<S3Event, String> {

    private static final Logger logger = Logger.getLogger(PDFFileProcessor.class.getName());
    private static final S3Client s3Client = S3Client.builder().build();

    private final String bucketName;

    public PDFFileProcessor() {
        this.bucketName = System.getenv("BUCKET_NAME");
        if (this.bucketName == null || this.bucketName.isEmpty()) {
            logger.severe("BUCKET_NAME environment variable is not set. This function may not operate correctly.");
        }
        logger.info("PDFFileProcessor initialized. Target Bucket: " + bucketName);
    }

    @Override
    public String handleRequest(S3Event event, Context context) {
        logger.info("Received S3 event for PDF processing. Request ID: " + context.getAwsRequestId());
        long startTime = System.currentTimeMillis();

        if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
            logger.warning("No records found in the S3 event.");
            return "No records to process.";
        }

        List<String> failedFiles = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();

        for (S3EventNotificationRecord record : event.getRecords()) {
            String rawKey = record.getS3().getObject().getKey();
            String s3Bucket = record.getS3().getBucket().getName();
            String s3Key = null;

            try {
                s3Key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                logger.info(String.format("Processing record for S3 object: s3://%s/%s (decoded: %s)", s3Bucket, rawKey, s3Key));

                if (!s3Bucket.equals(this.bucketName)) {
                    logger.warning(String.format("Event bucket '%s' doesn't match expected bucket '%s'. Skipping: %s", s3Bucket, bucketName, s3Key));
                    skippedFiles.add(s3Key);
                    continue;
                }

                if (!s3Key.toLowerCase().endsWith(".pdf")) {
                    logger.warning("Skipped non-PDF file: " + s3Key);
                    skippedFiles.add(s3Key);
                    continue;
                }

                Path localFilePath = null;

                try {
                    localFilePath = SharedLambdaLayer.downloadFileAsPath(s3Client, s3Bucket, s3Key);
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
                    processPdfFile(localFilePath);
                } catch (NoSuchKeyException e) {
                    logger.severe(String.format("File not found on S3: %s/%s - %s", s3Bucket, s3Key, e.getMessage()));
                    failedFiles.add(s3Key);
                } catch (S3Exception e) {
                    logger.severe(String.format("S3 error while downloading: %s - %s", s3Key, e.getMessage()));
                    failedFiles.add(s3Key);
                } catch (IOException e) {
                    logger.severe(String.format("I/O error while processing PDF: %s - %s", s3Key, e.getMessage()));
                    failedFiles.add(s3Key);
                } finally {
                    if (localFilePath != null) {
                        SharedLambdaLayer.cleanUpFile(localFilePath);
                    }
                }

            } catch (Exception e) {
                logger.severe(String.format("Unhandled exception for key %s: %s", rawKey, e.getMessage()));
                failedFiles.add(s3Key != null ? s3Key : rawKey + " (decode_failed)");
            }
        }

        long endTime = System.currentTimeMillis();
        logger.info(String.format("PDF processing completed in %d ms", (endTime - startTime)));

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

    private void processPdfFile(Path filePath) {
        logger.info("Starting PDF content extraction: " + filePath.toString());

        try (PDDocument document = PDDocument.load(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            logger.info("Extracted text (first 200 chars): " + text.substring(0, Math.min(text.length(), 200)) + "...");
        } catch (IOException e) {
            logger.severe("Failed to parse PDF: " + e.getMessage());
            throw new RuntimeException("PDF parsing failed", e);
        }

        logger.info("Finished PDF content processing for: " + filePath);
    }
}
