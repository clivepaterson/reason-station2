package org.reasonstation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class S3StorageService {
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration downloadUrlTtl;

    public S3StorageService(
            S3Client client,
            S3Presigner presigner,
            @Value("${app.storage.s3.bucket}") String bucket,
            @Value("${app.storage.s3.download-url-ttl:PT5M}") Duration downloadUrlTtl) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.downloadUrlTtl = downloadUrlTtl;
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

    public URI createDownloadUrl(String objectUri, String filename) {
        URI uri = URI.create(objectUri);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(uri.getHost())
                .key(uri.getPath().substring(1))
                .responseContentType("application/octet-stream")
                .responseContentDisposition(ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(downloadUrlTtl)
                .getObjectRequest(request)
                .build();
        return URI.create(presigner.presignGetObject(presignRequest).url().toString());
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
