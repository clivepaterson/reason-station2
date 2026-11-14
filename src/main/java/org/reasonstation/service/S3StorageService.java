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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

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
        return presignDownload(uri.getHost(), uri.getPath().substring(1), filename);
    }

    public URI createArchiveDownloadUrl(String objectUri, String filename) {
        // Archive imports store raw object keys, including spaces and reserved characters.
        String[] parts = objectUri.split("/", 4);
        if (parts.length != 4 || !parts[0].equals("s3:") || !parts[1].isEmpty()
                || parts[2].isBlank() || parts[3].isBlank()) {
            throw new IllegalArgumentException("Archive object reference must be s3://bucket/key.");
        }
        return presignDownload(parts[2], parts[3], filename);
    }

    private URI presignDownload(String bucketName, String objectKey, String filename) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
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

    public URI createImageUrl(String objectUri) {
        URI uri = URI.create(objectUri);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(uri.getHost())
                .key(uri.getPath().substring(1))
                .responseContentDisposition("inline")
                .build();
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(downloadUrlTtl)
                .getObjectRequest(request)
                .build()).url().toString());
    }

    public long getFilesize(String objectUri) {
        URI uri = URI.create(objectUri);
        return client.headObject(HeadObjectRequest.builder()
                .bucket(uri.getHost())
                .key(uri.getPath().substring(1))
                .build()).contentLength();
    }

    public Optional<byte[]> readOptional(String objectUri, int maxBytes) throws IOException {
        URI uri = URI.create(objectUri);
        try (var input = client.getObject(GetObjectRequest.builder()
                .bucket(uri.getHost()).key(uri.getPath().substring(1)).build())) {
            byte[] content = input.readNBytes(maxBytes + 1);
            if (content.length > maxBytes) {
                throw new IOException("Stored object exceeds the allowed size.");
            }
            return Optional.of(content);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    public void downloadTo(String objectUri, Path destination, long maxBytes) throws IOException {
        URI uri = URI.create(objectUri);
        try (var input = client.getObject(GetObjectRequest.builder()
                .bucket(uri.getHost()).key(uri.getPath().substring(1)).build());
             OutputStream output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new IOException("Audio exceeds the waveform processing size limit.");
                }
                output.write(buffer, 0, count);
            }
        }
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
