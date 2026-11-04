package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class S3StorageServiceTest {
    @Test
    void createsPresignedDownloadUrlUsingPublicEndpoint() {
        try (S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create("https://storage.example"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("access-key", "secret-key")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build()) {
            S3StorageService storage = new S3StorageService(
                    mock(S3Client.class), presigner, "tracks", Duration.ofMinutes(5));

            URI downloadUrl = storage.createDownloadUrl(
                    "s3://tracks/tracks/song", "my song.reason");

            assertEquals("storage.example", downloadUrl.getHost());
            assertEquals("/tracks/tracks/song", downloadUrl.getPath());
            assertTrue(downloadUrl.getRawQuery().contains("response-content-disposition="));
            assertTrue(downloadUrl.getRawQuery().contains("X-Amz-Signature="));

            URI imageUrl = storage.createImageUrl("s3://tracks/patch-images/image");
            assertEquals("/tracks/patch-images/image", imageUrl.getPath());
            assertTrue(imageUrl.getRawQuery().contains("response-content-disposition=inline"));
            assertTrue(imageUrl.getRawQuery().contains("X-Amz-Signature="));
            assertTrue(!imageUrl.getRawQuery().contains("response-content-type="));
        }
    }
}
