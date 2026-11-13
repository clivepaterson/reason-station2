package org.reasonstation.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageFileFormatTest {
    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png", "bmp", "gif"})
    void acceptsMatchingImageFormats(String format) throws Exception {
        assertTrue(ImageFileFormat.matches(format, image(format)));
    }

    @Test
    void rejectsWrongExtensionAndInvalidImages() throws Exception {
        assertFalse(ImageFileFormat.matches("jpeg", image("png")));
        assertFalse(ImageFileFormat.matches("png", new byte[]{1, 2, 3}));
        assertFalse(ImageFileFormat.matches("png", new byte[0]));
        assertFalse(ImageFileFormat.matches("png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}));
    }

    private byte[] image(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), format, output));
        return output.toByteArray();
    }
}
