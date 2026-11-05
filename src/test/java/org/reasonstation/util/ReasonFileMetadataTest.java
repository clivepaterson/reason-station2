package org.reasonstation.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasonFileMetadataTest {
    private static final byte[] MARKER = "PROGRAMVERSIONBODY".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({"724, 13.2.1", "262892, 10.4.0", "65530, 13.2.1", "0, 10.4.0"})
    void findsVersionAtVariableOffsets(int offset, String version) {
        assertEquals(Optional.of(version), ReasonFileMetadata.programVersion(metadata(offset, version)));
    }

    @Test
    void returnsEmptyWhenMetadataIsMissing() {
        assertEquals(Optional.empty(), ReasonFileMetadata.programVersion(new byte[0]));
        assertEquals(Optional.empty(), ReasonFileMetadata.programVersion(
                "X15_FILE 13.2.1".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void ignoresTruncatedMetadata() {
        byte[] content = metadata(0, "13.2.1");
        for (int length = 0; length < content.length; length++) {
            assertEquals(Optional.empty(),
                    ReasonFileMetadata.programVersion(Arrays.copyOf(content, length)));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65, Integer.MAX_VALUE})
    void ignoresInvalidVersionLengths(int length) {
        byte[] content = metadata(0, "13.2.1");
        ByteBuffer.wrap(content).putInt(MARKER.length + Integer.BYTES, length);
        assertEquals(Optional.empty(), ReasonFileMetadata.programVersion(content));
    }

    @Test
    void ignoresInconsistentBodyLength() {
        byte[] content = metadata(0, "13.2.1");
        ByteBuffer.wrap(content).putInt(MARKER.length, 999);
        assertEquals(Optional.empty(), ReasonFileMetadata.programVersion(content));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Reason", "13.2.x", "13.2.1\n", "13..1", "13.2.1<script>"})
    void ignoresInvalidVersionValues(String version) {
        assertEquals(Optional.empty(), ReasonFileMetadata.programVersion(metadata(0, version)));
    }

    @Test
    void continuesPastInvalidMetadataToFindValidVersion() {
        byte[] invalid = metadata(0, "invalid");
        byte[] valid = metadata(0, "10.4.0");
        byte[] content = ByteBuffer.allocate(invalid.length + valid.length)
                .put(invalid).put(valid).array();
        assertEquals(Optional.of("10.4.0"), ReasonFileMetadata.programVersion(content));
    }

    private byte[] metadata(int offset, String version) {
        byte[] value = version.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(offset + MARKER.length + 2 * Integer.BYTES + value.length)
                .position(offset)
                .put(MARKER)
                .putInt(Integer.BYTES + value.length)
                .putInt(value.length)
                .put(value)
                .array();
    }
}
