package org.reasonstation.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mp3FileFormatTest {
    @ParameterizedTest
    @ValueSource(ints = {3, 2, 0})
    void acceptsCompleteLayerThreeFrames(int version) {
        assertTrue(Mp3FileFormat.matches(frame(version)));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void acceptsId3TagsFollowedByAudio(int tagVersion) {
        byte[] frame = frame(3);
        byte[] content = ByteBuffer.allocate(13 + frame.length)
                .put(new byte[]{'I', 'D', '3', (byte) tagVersion, 0, 0, 0, 0, 0, 3})
                .put(new byte[]{1, 2, 3}).put(frame).array();
        assertTrue(Mp3FileFormat.matches(content));
    }

    @Test
    void acceptsId3VersionFourFooter() {
        byte[] frame = frame(3);
        byte[] content = ByteBuffer.allocate(20 + frame.length)
                .put(new byte[]{'I', 'D', '3', 4, 0, 0x10, 0, 0, 0, 0})
                .position(20).put(frame).array();
        assertTrue(Mp3FileFormat.matches(content));
    }

    @Test
    void rejectsTruncatedFramesAndHeaderOnlyFiles() {
        assertFalse(Mp3FileFormat.matches(new byte[0]));
        assertFalse(Mp3FileFormat.matches(new byte[]{'I', 'D', '3'}));
        assertFalse(Mp3FileFormat.matches(new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0}));
        assertFalse(Mp3FileFormat.matches(Arrays.copyOf(frame(3), 416)));
        assertFalse(Mp3FileFormat.matches(new byte[]{(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0}));
    }

    @Test
    void rejectsOtherAudioFormatsAndInvalidHeaders() {
        assertFalse(Mp3FileFormat.matches("RIFF0000WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        byte[] frame = frame(3);
        frame[1] = (byte) 0xfd; // MPEG Layer II is not MP3.
        assertFalse(Mp3FileFormat.matches(frame));
        frame[1] = (byte) 0xeb; // Reserved MPEG version.
        assertFalse(Mp3FileFormat.matches(frame));
        frame[1] = (byte) 0xfb;
        frame[2] = (byte) 0xfc;
        assertFalse(Mp3FileFormat.matches(frame));
    }

    @Test
    void rejectsMalformedId3Size() {
        byte[] content = ByteBuffer.allocate(427)
                .put(new byte[]{'I', 'D', '3', 3, 0, 0, (byte) 0x80, 0, 0, 0})
                .put(frame(3)).array();
        assertFalse(Mp3FileFormat.matches(content));
        content[6] = 0x7f;
        assertFalse(Mp3FileFormat.matches(content));
    }

    private byte[] frame(int version) {
        byte[] frame = new byte[version == 3 ? 417 : version == 2 ? 261 : 522];
        frame[0] = (byte) 0xff;
        frame[1] = (byte) (0xe3 | (version << 3));
        frame[2] = (byte) 0x90;
        return frame;
    }
}
