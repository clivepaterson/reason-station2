package org.reasonstation.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ReasonFileMetadata {
    private static final byte[] PROGRAM_VERSION_BODY =
            "PROGRAMVERSIONBODY".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_VERSION_LENGTH = 64;
    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9]+(?:\\.[0-9]+)+");

    private ReasonFileMetadata() {
    }

    public static Optional<String> programVersion(byte[] content) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        int headerLength = PROGRAM_VERSION_BODY.length + 2 * Integer.BYTES;
        for (int offset = 0; offset <= content.length - headerLength; offset++) {
            if (content[offset] != PROGRAM_VERSION_BODY[0]
                    || !Arrays.equals(content, offset, offset + PROGRAM_VERSION_BODY.length,
                    PROGRAM_VERSION_BODY, 0, PROGRAM_VERSION_BODY.length)) {
                continue;
            }

            // BODY stores a big-endian chunk length, then a length-prefixed ASCII value.
            int bodyOffset = offset + PROGRAM_VERSION_BODY.length;
            int bodyLength = buffer.getInt(bodyOffset);
            int versionLength = buffer.getInt(bodyOffset + Integer.BYTES);
            int valueOffset = offset + headerLength;
            if (versionLength < 1 || versionLength > MAX_VERSION_LENGTH
                    || bodyLength != Integer.BYTES + versionLength
                    || versionLength > content.length - valueOffset) {
                continue;
            }

            String version = new String(content, valueOffset, versionLength, StandardCharsets.US_ASCII);
            if (VERSION_PATTERN.matcher(version).matches()) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
