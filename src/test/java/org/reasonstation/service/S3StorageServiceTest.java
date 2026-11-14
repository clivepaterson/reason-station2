package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class S3StorageServiceTest {
    @TempDir Path directory;

    @Test
    void readsOptionalObjectsAndDoesNotHideAccessDenied() throws Exception {
        S3Client client = mock(S3Client.class);
        var storage = new S3StorageService(client, mock(S3Presigner.class), "tracks", Duration.ofMinutes(5));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(object(new byte[]{1, 2, 3}));
        assertArrayEquals(new byte[]{1, 2, 3}, storage.readOptional("s3://tracks/peaks", 3).orElseThrow());
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(
                S3Exception.builder().statusCode(404).message("Missing").build());
        assertTrue(storage.readOptional("s3://tracks/missing", 3).isEmpty());
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(
                S3Exception.builder().statusCode(403).message("Forbidden").build());
        assertThrows(S3Exception.class, () -> storage.readOptional("s3://tracks/forbidden", 3));
    }

    @Test
    void boundsStoredWaveformReadsAndAudioDownloads() throws Exception {
        S3Client client = mock(S3Client.class);
        var storage = new S3StorageService(client, mock(S3Presigner.class), "tracks", Duration.ofMinutes(5));
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> object(new byte[]{1, 2, 3}));
        assertThrows(IOException.class, () -> storage.readOptional("s3://tracks/peaks", 2));
        Path audio = directory.resolve("input.mp3");
        storage.downloadTo("s3://tracks/audio", audio, 3);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(audio));
        assertThrows(IOException.class, () -> storage.downloadTo("s3://tracks/audio", audio, 2));
    }

    private ResponseInputStream<GetObjectResponse> object(byte[] content) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) content.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(content)));
    }

    @Test
    void readsFilesizeFromObjectMetadata() {
        S3Client client = mock(S3Client.class);
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket("tracks").key("track-audio/preview").build();
        when(client.headObject(request)).thenReturn(HeadObjectResponse.builder().contentLength(1572864L).build());
        S3StorageService storage = new S3StorageService(
                client, mock(S3Presigner.class), "tracks", Duration.ofMinutes(5));

        assertEquals(1572864L, storage.getFilesize("s3://tracks/track-audio/preview"));
        verify(client).headObject(request);
    }

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

            String archiveKey = "songs/2007/vibrant energy #1%?.rps";
            URI archiveUrl = storage.createArchiveDownloadUrl(
                    "s3://reasonarchive/" + archiveKey, "vibrant energy #1%?.rps");
            assertEquals("storage.example", archiveUrl.getHost());
            assertEquals("/reasonarchive/" + archiveKey, archiveUrl.getPath());
            assertTrue(archiveUrl.getRawQuery().contains("response-content-disposition="));
            assertTrue(archiveUrl.getRawQuery().contains("X-Amz-Signature="));
            assertThrows(IllegalArgumentException.class,
                    () -> storage.createArchiveDownloadUrl("https://example.com/file", "file"));
            assertThrows(IllegalArgumentException.class,
                    () -> storage.createArchiveDownloadUrl("s3://reasonarchive/", "file"));
        }
    }
}
