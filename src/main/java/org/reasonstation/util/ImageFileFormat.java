package org.reasonstation.util;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public final class ImageFileFormat {
    private ImageFileFormat() {
    }

    public static boolean matches(String format, byte[] content) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            var reader = readers.next();
            try {
                if (!format.equalsIgnoreCase(reader.getFormatName())) {
                    return false;
                }
                reader.setInput(input);
                return reader.getWidth(0) > 0 && reader.getHeight(0) > 0;
            } finally {
                reader.dispose();
            }
        } catch (IIOException exception) {
            return false;
        }
    }
}
