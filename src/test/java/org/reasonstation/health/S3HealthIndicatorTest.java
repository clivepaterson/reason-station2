package org.reasonstation.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3HealthIndicatorTest {
    private final S3Client client = mock(S3Client.class);
    private final S3HealthIndicator indicator = new S3HealthIndicator(client, "tracks");

    @Test
    void reportsUpWhenTheBucketIsReachable() {
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWithoutDetailsWhenTheBucketIsUnreachable() {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(SdkClientException.create("connection failed"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }
}
