package org.reasonstation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;

@Service
public class S3StorageService {
    private final S3Client client;
    private final String bucket;

    public S3StorageService(
            S3Client client,
            @Value("${app.storage.s3.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    public String upload(String objectKey, byte[] content, String contentType) {
        ensureBucket();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .build();
        client.putObject(request, RequestBody.fromBytes(content));
        return "s3://" + bucket + "/" + objectKey;
    }

    public byte[] download(String trackUri) {
        URI uri = URI.create(trackUri);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(uri.getHost())
                .key(uri.getPath().substring(1))
                .build();
        return client.getObjectAsBytes(request).asByteArray();
    }

    public void delete(String trackUri) {
        URI uri = URI.create(trackUri);
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(uri.getHost())
                .key(uri.getPath().substring(1))
                .build());
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException exception) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }
}
