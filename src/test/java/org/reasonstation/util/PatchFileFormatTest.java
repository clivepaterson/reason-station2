package org.reasonstation.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchFileFormatTest {
    @ParameterizedTest
    @ValueSource(strings = {".patch", ".repatch", ".PATCH", ".REPATCH"})
    void acceptsJukeboxPatchXml(String extension) {
        String xml = """
                <?xml version="1.0"?>
                <JukeboxPatch version="2.0" deviceProductID="se.propellerheads.Mimic" deviceVersion="1.2.10">
                    <DeviceNameInEnglish>Mimic Creative Sampler</DeviceNameInEnglish>
                </JukeboxPatch>
                """;

        assertTrue(PatchFileFormat.matches(extension, xml.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<JukeboxPatch deviceProductID='se.propellerheads.Grain'/>",
            "\uFEFF<?xml version='1.0'?><JukeboxPatch deviceProductID='se.propellerheads.Polytone'/>",
            "<!-- comment --><JukeboxPatch deviceProductID='se.propellerheads.Mimic'><Properties name='A &amp; B'/></JukeboxPatch>"
    })
    void acceptsXmlVariations(String xml) {
        assertTrue(PatchFileFormat.matches(".patch", xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void honorsDeclaredXmlEncoding() {
        String xml = "<?xml version='1.0' encoding='UTF-16'?><JukeboxPatch deviceProductID='se.propellerheads.Mimic'/>";

        assertTrue(PatchFileFormat.matches(".repatch", xml.getBytes(StandardCharsets.UTF_16)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "FORM",
            "<JukeboxPatch deviceProductID='se.propellerheads.Mimic'>",
            "<JukeboxPatch deviceProductID='se.propellerheads.Mimic'><Child></JukeboxPatch>",
            "<JukeboxPatch deviceProductID='se.propellerheads.Mimic'/>trailing garbage",
            "<JukeboxPatch deviceProductID='se.propellerheads.Mimic'/><Other/>",
            "<Other><JukeboxPatch deviceProductID='se.propellerheads.Mimic'/></Other>",
            "<!-- JukeboxPatch deviceProductID='se.propellerheads.Mimic' --><Other/>",
            "<JukeboxPatch/>",
            "<JukeboxPatch deviceProductID=''/>",
            "<JukeboxPatch deviceProductID='another.vendor'/>",
            "<JukeboxPatch><Child deviceProductID='se.propellerheads.Mimic'/></JukeboxPatch>",
            "<Other deviceProductID='se.propellerheads.Mimic'/>",
            "<jukeboxpatch deviceProductID='se.propellerheads.Mimic'/>",
            "<JukeboxPatch xmlns='urn:other' deviceProductID='se.propellerheads.Mimic'/>",
            "<JukeboxPatch xmlns:x='urn:other' x:deviceProductID='se.propellerheads.Mimic'/>",
            "<!DOCTYPE JukeboxPatch><JukeboxPatch deviceProductID='se.propellerheads.Mimic'/>",
            "<!DOCTYPE JukeboxPatch SYSTEM 'https://example.invalid/patch.dtd'><JukeboxPatch deviceProductID='se.propellerheads.Mimic'/>",
            "<!DOCTYPE JukeboxPatch [<!ENTITY vendor 'propellerheads'>]><JukeboxPatch deviceProductID='se.&vendor;.Mimic'/>",
            "<!DOCTYPE JukeboxPatch [<!ENTITY xxe SYSTEM 'file:///nonexistent-patch-validation-file'>]><JukeboxPatch deviceProductID='se.propellerheads.Mimic'>&xxe;</JukeboxPatch>"
    })
    void rejectsInvalidOrUnsafeXml(String xml) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        assertFalse(PatchFileFormat.matches(".patch", bytes));
        assertFalse(PatchFileFormat.matches(".repatch", bytes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "F", "FO", "FOR", "form", "prefixFORM", "<?xml version='1.0'?>"})
    void rejectsMissingOrMisplacedFormHeader(String content) {
        assertFalse(PatchFileFormat.matches(".thor", content.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void acceptsFormHeaderFollowedByBinaryBytes() {
        assertTrue(PatchFileFormat.matches(".sxt", new byte[]{'F', 'O', 'R', 'M', 0, 1, (byte) 255}));
    }

    @ParameterizedTest
    @ValueSource(strings = {".rx2", ".RX2"})
    void acceptsRex2Container(String extension) {
        byte[] content = {'C', 'A', 'T', ' ', 0, 5, 0x6b, (byte) 0xbc,
                'R', 'E', 'X', '2', 'H', 'E', 'A', 'D'};

        assertTrue(PatchFileFormat.matches(extension, content));
        assertTrue(PatchFileFormat.matches(extension, "FORM".getBytes(StandardCharsets.US_ASCII)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "CAT ", "CAT \0\0\0", "CAT \0\0\0\0", "CAT \0\0\0\0REX",
            "cat \0\0\0\0REX2", "CATX\0\0\0\0REX2", "CAT \0\0\0\0rex2",
            "CAT \0\0\0\0WAVE", "CAT REX2", "prefixCAT \0\0\0\0REX2"
    })
    void rejectsInvalidRex2Headers(String content) {
        assertFalse(PatchFileFormat.matches(".rx2", content.getBytes(StandardCharsets.US_ASCII)));
    }

    @ParameterizedTest
    @ValueSource(strings = {".thor", ".sxt", ".zyp", ".rex", ".rcy", ".patch", ".repatch"})
    void rejectsRex2ContainerForOtherFormats(String extension) {
        byte[] content = "CAT \0\0\0\0REX2".getBytes(StandardCharsets.US_ASCII);

        assertFalse(PatchFileFormat.matches(extension, content));
    }

    @ParameterizedTest
    @ValueSource(strings = {".zyp", ".ZYP"})
    void acceptsLegacySubtractorHeader(String extension) {
        byte[] content = "Propellerheads Reason SubTractor Patch File\u001a\0\0\u00ff"
                .getBytes(StandardCharsets.ISO_8859_1);

        assertTrue(PatchFileFormat.matches(extension, content));
        assertTrue(PatchFileFormat.matches(extension, "FORM".getBytes(StandardCharsets.US_ASCII)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "Propellerheads Reason SubTractor Patch",
            "Propellerheads Reason SubTractor Patch File",
            "Propellerheads Reason SubTractor Patch File\0",
            "propellerheads Reason SubTractor Patch File\u001a",
            "prefixPropellerheads Reason SubTractor Patch File\u001a",
            "Propellerheads Reason Other Patch File\u001a"
    })
    void rejectsInvalidSubtractorHeaders(String content) {
        assertFalse(PatchFileFormat.matches(".zyp", content.getBytes(StandardCharsets.US_ASCII)));
    }

    @ParameterizedTest
    @ValueSource(strings = {".thor", ".sxt", ".patch", ".repatch"})
    void rejectsLegacySubtractorHeaderForOtherFormats(String extension) {
        byte[] content = "Propellerheads Reason SubTractor Patch File\u001a"
                .getBytes(StandardCharsets.US_ASCII);

        assertFalse(PatchFileFormat.matches(extension, content));
    }
}
