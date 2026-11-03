package org.reasonstation.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@Component
public class S3HealthIndicator implements HealthIndicator {
    private final S3Client client;
    private final String bucket;

    public S3HealthIndicator(
            S3Client client,
            @Value("${app.storage.s3.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return Health.up().build();
        } catch (SdkException exception) {
            return Health.down().build();
        }
    }
}
