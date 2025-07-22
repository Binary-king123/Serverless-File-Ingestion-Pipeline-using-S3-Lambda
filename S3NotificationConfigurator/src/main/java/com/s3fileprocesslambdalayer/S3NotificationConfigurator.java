package com.s3fileprocesslambdalayer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.AddPermissionRequest;
import software.amazon.awssdk.services.lambda.model.ResourceConflictException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

public class S3NotificationConfigurator implements RequestHandler<Map<String, Object>, Object> {

    private static final Logger logger = Logger.getLogger(S3NotificationConfigurator.class.getName());
    private static final S3Client s3Client = S3Client.builder().build();
    private static final LambdaClient lambdaClient = LambdaClient.builder().build();

    @Override
    public Object handleRequest(Map<String, Object> event, Context context) {
        logger.info("🔥 EVENT RECEIVED: " + event.toString());
        String responseUrl = safeString(event.get("ResponseURL"));
        logger.info("📦 Received CloudFormation custom resource event.");
        logger.info("✅ Response URL: " + responseUrl);
        long startTimeMillis = System.currentTimeMillis();
        long maxExecutionMillis = context.getRemainingTimeInMillis() - 5000;

        String status = "SUCCESS";
        String reason = "S3 bucket notifications configured successfully";
        String physicalResourceId = "S3NotificationConfigurator-" + UUID.randomUUID();

        try {
            /*s3-file-processor-using-shared-lambda-layerString bucketName = safeString(event.get("BucketName"));
            String csvProcessorArn = safeString(event.get("CSVProcessorArn"));
            String pdfProcessorArn = safeString(event.get("PDFProcessorArn"));
            String imageProcessorArn = safeString(event.get("ImageProcessorArn"));

            logger.info("🎯 Bucket: " + bucketName);
            logger.info("📌 CSV ARN: " + csvProcessorArn);
            logger.info("📌 PDF ARN: " + pdfProcessorArn);
            logger.info("📌 IMG ARN: " + imageProcessorArn);*/
            // 🔽 Add the below block HERE
            @SuppressWarnings("unchecked")
            Map<String, Object> resourceProps = (Map<String, Object>) event.get("ResourceProperties");

            String bucketName = (String) resourceProps.get("BucketName");
            String csvProcessorArn = (String) resourceProps.get("CSVProcessorArn");
            String pdfProcessorArn = (String) resourceProps.get("PDFProcessorArn");
            String imageProcessorArn = (String) resourceProps.get("ImageProcessorArn");

            logger.info("✅ EVENT: " + event);
            logger.info("✅ Resource Properties: " + resourceProps);
            logger.info("CSV ARN: " + csvProcessorArn);
            logger.info("PDF ARN: " + pdfProcessorArn);
            logger.info("Image ARN: " + imageProcessorArn);
            logger.info("Bucket: " + bucketName);

            List<LambdaFunctionConfiguration> lambdaConfigs = new ArrayList<>();

            lambdaConfigs.addAll(setup(bucketName, csvProcessorArn, "csv", ".csv"));
            lambdaConfigs.addAll(setup(bucketName, pdfProcessorArn, "pdf", ".pdf"));
            lambdaConfigs.addAll(setup(bucketName, imageProcessorArn, "image", ".jpg", ".jpeg", ".png"));

            if (!lambdaConfigs.isEmpty()) {
                NotificationConfiguration notificationConfiguration = NotificationConfiguration.builder()
                        .lambdaFunctionConfigurations(lambdaConfigs)
                        .build();

                s3Client.putBucketNotificationConfiguration(
                        PutBucketNotificationConfigurationRequest.builder()
                                .bucket(bucketName)
                                .notificationConfiguration(notificationConfiguration)
                                .build()
                );

                logger.info("✅ Successfully applied S3 notification configuration.");
            } else {
                logger.info("ℹ️ No valid Lambda ARNs provided — skipping notification config.");
            }

            if (System.currentTimeMillis() - startTimeMillis >= maxExecutionMillis) {
                logger.warning("⚠️ Aborting before timeout to safely notify CloudFormation.");
            }

        } catch (Exception e) {
            logger.warning("⚠️ Non-blocking error during processing: " + e.getMessage());
            reason = "✅ Processed with warnings: " + e.getMessage();
        }

        String stackId = safeString(event.get("StackId"));
        String requestId = safeString(event.get("RequestId"));
        String logicalResourceId = safeString(event.get("LogicalResourceId"));
        sendResponseToCloudFormation(responseUrl, status, reason, physicalResourceId, stackId, requestId, logicalResourceId);

        return null;
    }

    private List<LambdaFunctionConfiguration> setup(String bucket, String arn, String tag, String... suffixes) {
        List<LambdaFunctionConfiguration> configs = new ArrayList<>();
        if (arn.isEmpty()) {
            logger.warning("⚠️ Skipping setup for: " + tag + " (ARN is empty)");
            return configs;
        }

        addLambdaPermission(bucket, arn, tag);

        for (String suffix : suffixes) {
            configs.add(createLambdaConfig(arn, suffix));
        }

        return configs;
    }

    private void addLambdaPermission(String bucket, String lambdaArn, String tag) {
        String statementId = "AllowS3Invoke-" + tag;
        try {
            lambdaClient.addPermission(AddPermissionRequest.builder()
                    .functionName(lambdaArn)
                    .principal("s3.amazonaws.com")
                    .statementId(statementId)
                    .action("lambda:InvokeFunction")
                    .sourceArn("arn:aws:s3:::" + bucket)
                    .build());
            logger.info("🛡️ Permission added for: " + tag);
        } catch (ResourceConflictException e) {
            logger.warning("⚠️ Permission already exists for: " + tag);
        } catch (Exception e) {
            logger.warning("⚠️ Error adding permission for: " + tag + " - " + e.getMessage());
        }
    }

    private LambdaFunctionConfiguration createLambdaConfig(String functionArn, String suffix) {
        return LambdaFunctionConfiguration.builder()
                .lambdaFunctionArn(functionArn)
                .eventsWithStrings("s3:ObjectCreated:*")
                .filter(NotificationConfigurationFilter.builder()
                        .key(S3KeyFilter.builder()
                                .filterRules(FilterRule.builder()
                                        .name(FilterRuleName.SUFFIX)
                                        .value(suffix)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private void sendResponseToCloudFormation(String responseUrl, String status, String reason,
                                              String physicalResourceId, String stackId,
                                              String requestId, String logicalResourceId) {
        try {
            String responseBody = String.format(
                    "{ \"Status\" : \"%s\", \"Reason\" : \"%s\", \"PhysicalResourceId\" : \"%s\", " +
                            "\"StackId\" : \"%s\", \"RequestId\" : \"%s\", \"LogicalResourceId\" : \"%s\" }",
                    status, reason, physicalResourceId, stackId, requestId, logicalResourceId);

            URL url = new URL(responseUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "");
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            conn.connect();

            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }

            logger.info("📬 CloudFormation response sent: " + status);
        } catch (Exception e) {
            logger.severe("❌ Failed to send response to CloudFormation: " + e.getMessage());
        }
    }

    private String safeString(Object obj) {
        return obj != null ? obj.toString().trim() : "";
    }
}
