package org.reasonstation.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasonFileFormatTest {
    @ParameterizedTest
    @CsvSource({
            "song.reason, X15_FILE",
            "song.REASON, X15_FILE",
            "song.rns, Propellerheads Reason Song File",
            "song.RNS, Propellerheads Reason Song File",
            "song.rps, FORM0000RPS2",
            "song.RPS, FORM0000RPS2"
    })
    void acceptsMatchingSignaturesAndRejectsEveryTruncatedHeader(String filename, String header) {
        byte[] content = header.getBytes(StandardCharsets.US_ASCII);
        assertTrue(ReasonFileFormat.matches(filename, content));
        for (int length = 0; length < content.length; length++) {
            assertFalse(ReasonFileFormat.matches(filename, Arrays.copyOf(content, length)));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "song.reason, x15_file",
            "song.reason, prefixX15_FILE",
            "song.reason, Propellerheads Reason Song File",
            "song.rns, X15_FILE",
            "song.rns, propellerheads Reason Song File",
            "song.rps, FORM0000AIFFReason",
            "song.rps, FORM0000Reason",
            "song.rps, FORM00000RPS2",
            "song.rps, XXXX0000RPS2",
            "song.txt, X15_FILE",
            "song, X15_FILE"
    })
    void rejectsMismatchedOrMisplacedSignatures(String filename, String header) {
        assertFalse(ReasonFileFormat.matches(filename, header.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void acceptsRpsWithBinaryLengthAndWithoutReasonInTheSongTitle() {
        byte[] content = ByteBuffer.allocate(20)
                .put("FORM".getBytes(StandardCharsets.US_ASCII))
                .putInt(12)
                .put("RPS2CAT ".getBytes(StandardCharsets.US_ASCII))
                .putInt(0)
                .array();

        assertTrue(ReasonFileFormat.matches("song.rps", content));
    }

    @ParameterizedTest
    @ValueSource(strings = {"song.reason", "song.rns", "song.rps"})
    void rejectsArbitraryBinaryContent(String filename) {
        assertFalse(ReasonFileFormat.matches(filename, new byte[]{0, 1, 2, 3, 4}));
    }
}
