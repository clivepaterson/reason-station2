package org.reasonstation.util;

public final class Mp3FileFormat {
    private static final int[] MPEG1_BITRATES = {
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320
    };
    private static final int[] MPEG2_BITRATES = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160
    };
    private static final int[] SAMPLE_RATES = {44100, 48000, 32000};

    private Mp3FileFormat() {
    }

    public static boolean matches(byte[] content) {
        int offset = 0;
        if (content.length >= 3 && content[0] == 'I' && content[1] == 'D' && content[2] == '3') {
            if (content.length < 10 || content[3] < 2 || content[3] > 4
                    || (content[4] & 0xff) == 0xff) {
                return false;
            }
            int tagSize = 0;
            for (int index = 6; index < 10; index++) {
                if (content[index] < 0) {
                    return false;
                }
                tagSize = (tagSize << 7) | content[index];
            }
            offset = 10 + tagSize;
            if (content[3] == 4 && (content[5] & 0x10) != 0) {
                offset += 10;
            }
        }
        // Require a complete MPEG Layer III frame, not just an ID3 tag or a sync marker.
        if (offset > content.length - 4) {
            return false;
        }
        int header = content[offset + 1] & 0xff;
        int version = (header >> 3) & 3;
        int bitrateIndex = (content[offset + 2] >> 4) & 15;
        int sampleRateIndex = (content[offset + 2] >> 2) & 3;
        if ((content[offset] & 0xff) != 0xff || (header & 0xe0) != 0xe0
                || version == 1 || ((header >> 1) & 3) != 1
                || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
            return false;
        }
        int bitrate = (version == 3 ? MPEG1_BITRATES : MPEG2_BITRATES)[bitrateIndex];
        int sampleRate = SAMPLE_RATES[sampleRateIndex] / (version == 3 ? 1 : version == 2 ? 2 : 4);
        int padding = (content[offset + 2] >> 1) & 1;
        int frameSize = (version == 3 ? 144000 : 72000) * bitrate / sampleRate + padding;
        return frameSize <= content.length - offset;
    }
}
