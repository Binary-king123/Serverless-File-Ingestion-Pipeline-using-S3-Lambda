# Serverless-File-Ingestion-Pipeline-using-S3-Lambda
Serverless project to automatically process CSV, image, and PDF files uploaded to AWS S3 using Lambda functions and a shared Lambda layer.

In modern cloud-native architectures, processing diverse file types uploaded to Amazon S3 is a common requirement across data pipelines, content ingestion platforms, and automated document workflows. However, achieving a modular, scalable, and maintainable solution can be challenging due to issues like code duplication, event configuration complexity, and deployment limitations (e.g., circular dependencies in CloudFormation).

This project presents a serverless, event-driven file processing framework built with AWS Lambda (Java 17), Lambda Layers, and AWS SAM (Serverless Application Model). It automatically detects and processes various file formats—such as CSV, PDF, and image files—uploaded to S3 using a clean, layered design.
