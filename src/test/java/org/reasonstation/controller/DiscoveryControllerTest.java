package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.Patch;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.TrackRepository;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryControllerTest {
    @Mock
    private TrackRepository tracks;
    @Mock
    private PatchRepository patches;

    private DiscoveryController controller;

    @BeforeEach
    void setUp() {
        controller = new DiscoveryController(tracks, patches);
    }

    @Test
    void listsPatchesForArtistAlias() {
        var patch = new Patch(7L, "Artist", "Warm Bass", "Bass patch",
                "warm-bass.thor", ".thor", 1024L, "s3://patches/warm-bass");
        when(patches.findByArtistAliasOrderByDateUploadedDesc("Artist"))
                .thenReturn(List.of(patch));

        var response = controller.artistPatches("Artist");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, ((List<?>) response.getBody()).size());
        verify(patches).findByArtistAliasOrderByDateUploadedDesc("Artist");
    }
}
