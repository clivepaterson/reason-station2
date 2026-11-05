package org.reasonstation.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public final class ReasonFileFormat {
    private static final byte[] REASON_HEADER = "X15_FILE".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RNS_HEADER =
            "Propellerheads Reason Song File".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FORM_HEADER = "FORM".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RPS_TYPE = "RPS2".getBytes(StandardCharsets.US_ASCII);

    private ReasonFileFormat() {
    }

    public static boolean matches(String filename, byte[] content) {
        int extensionStart = filename.lastIndexOf('.');
        if (extensionStart < 0) {
            return false;
        }
        return switch (filename.substring(extensionStart).toLowerCase(Locale.ROOT)) {
            case ".reason" -> matchesAt(content, 0, REASON_HEADER);
            case ".rns" -> matchesAt(content, 0, RNS_HEADER);
            // FORM is followed by a four-byte chunk length, then the form type.
            case ".rps" -> matchesAt(content, 0, FORM_HEADER) && matchesAt(content, 8, RPS_TYPE);
            default -> false;
        };
    }

    private static boolean matchesAt(byte[] content, int offset, byte[] signature) {
        return content.length >= offset + signature.length
                && Arrays.equals(content, offset, offset + signature.length,
                signature, 0, signature.length);
    }
}
