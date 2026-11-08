package org.reasonstation.util;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PatchFileFormat {
    private static final byte[] FORM_HEADER = "FORM".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CAT_HEADER = "CAT ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] REX2_TYPE = "REX2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SUBTRACTOR_HEADER =
            "Propellerheads Reason SubTractor Patch File\u001a".getBytes(StandardCharsets.US_ASCII);

    private PatchFileFormat() {
    }

    public static boolean matches(String extension, byte[] content) {
        if (".patch".equalsIgnoreCase(extension) || ".repatch".equalsIgnoreCase(extension)) {
            return isJukeboxPatch(content);
        }
        return startsWith(content, FORM_HEADER)
                || (".rx2".equalsIgnoreCase(extension) && isRex2(content))
                || (".zyp".equalsIgnoreCase(extension) && startsWith(content, SUBTRACTOR_HEADER));
    }

    private static boolean isRex2(byte[] content) {
        return content.length >= 12 && startsWith(content, CAT_HEADER)
                && Arrays.equals(content, 8, 12, REX2_TYPE, 0, REX2_TYPE.length);
    }

    private static boolean startsWith(byte[] content, byte[] header) {
        return content.length >= header.length
                && Arrays.equals(content, 0, header.length, header, 0, header.length);
    }

    private static boolean isJukeboxPatch(byte[] content) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");

        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(content));
            try {
                boolean rootFound = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        return false;
                    }
                    if (event == XMLStreamConstants.START_ELEMENT && !rootFound) {
                        rootFound = true;
                        String productId = reader.getAttributeValue("", "deviceProductID");
                        if (!"JukeboxPatch".equals(reader.getLocalName())
                                || (reader.getNamespaceURI() != null && !reader.getNamespaceURI().isEmpty())
                                || productId == null || !productId.contains("propellerheads")) {
                            return false;
                        }
                    }
                }
                return rootFound;
            } finally {
                reader.close();
            }
        } catch (XMLStreamException exception) {
            return false;
        }
    }
}
