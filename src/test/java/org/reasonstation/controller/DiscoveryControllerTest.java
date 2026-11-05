package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.repository.PatchWithRating;
import org.reasonstation.repository.HighestRatedPatch;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.TrackRepository;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void listsTopTenHighestRatedPatches() throws Exception {
        var patch = new HighestRatedPatch(7L, "Artist", "Warm Bass", 8.5, 12L);
        when(patches.findHighestRatedPatches(10)).thenReturn(List.of(patch));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/highest-rated-patches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].patchid").value(7))
                .andExpect(jsonPath("$[0].artistAlias").value("Artist"))
                .andExpect(jsonPath("$[0].patchName").value("Warm Bass"))
                .andExpect(jsonPath("$[0].averagePatchRating").value(8.5))
                .andExpect(jsonPath("$[0].ratingCount").value(12));

        verify(patches).findHighestRatedPatches(10);
        verifyNoInteractions(tracks);
    }

    @Test
    void returnsEmptyChartWhenNoPatchesHaveRatings() throws Exception {
        when(patches.findHighestRatedPatches(10)).thenReturn(List.of());

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/highest-rated-patches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
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
