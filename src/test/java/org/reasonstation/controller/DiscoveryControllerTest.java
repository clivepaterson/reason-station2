package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.repository.PatchWithRating;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.TrackRepository;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        var patch = new PatchWithRating(7L, "Artist", "Warm Bass", "Bass patch",
                "warm-bass.thor", ".thor", 1024L, null, java.time.Instant.now(), 8.5);
        when(patches.findWithRatingsByArtistAlias("Artist"))
                .thenReturn(List.of(patch));

        var response = controller.artistPatches("Artist");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, ((List<?>) response.getBody()).size());
        assertEquals(8.5, ((PatchWithRating) ((List<?>) response.getBody()).getFirst()).averagePatchRating());
        verify(patches).findWithRatingsByArtistAlias("Artist");
    }

    @Test
    void trimsArtistAliasBeforeLookup() {
        when(tracks.findByArtistAlias("Artist")).thenReturn(List.of());

        var response = controller.artistTracks("  Artist  ");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(tracks).findByArtistAlias("Artist");
    }

    @Test
    void rejectsBlankArtistAlias() {
        var response = controller.artistTracks(" ");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(tracks);
    }

    @Test
    void rejectsMissingSearchTerm() {
        var response = controller.searchTracks(null, 1);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(tracks);
    }
}
