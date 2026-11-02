package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.User;
import org.reasonstation.entity.Track;
import org.reasonstation.repository.TrackDownloadRepository;
import org.reasonstation.repository.TrackRatingRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class TrackControllerTest {
    @Mock
    private TrackRepository tracks;
    @Mock
    private TrackDownloadRepository downloads;
    @Mock
    private TrackRatingRepository ratings;
    @Mock
    private UserRepository users;
    @Mock
    private S3StorageService storage;
    @Mock
    private Authentication authentication;

    private TrackController controller;

    @BeforeEach
    void setUp() {
        controller = new TrackController(tracks, downloads, ratings, users, storage);
    }

    @Test
    void rejectsTrackUploadWhenEmailIsNotVerified() throws IOException {
        User user = user(false);
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));

        var response = controller.uploadTrack(authentication, "Artist", "Track", "Rock", trackFile());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(Map.of("error", "Verify your email address before uploading tracks."),
                response.getBody());
        verify(storage, never()).upload(any(), any(), any());
        verify(tracks, never()).save(any());
    }

    @Test
    void uploadsTrackWhenEmailIsVerified() throws IOException {
        User user = user(true);
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/upload");
        when(tracks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.uploadTrack(
                authentication, "Artist Name", "Track Name", "Rock", trackFile());

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(storage).upload(any(), any(), any());
        ArgumentCaptor<Track> savedTrack = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(savedTrack.capture());
        assertEquals("Artist Name", savedTrack.getValue().getArtistAlias());
        assertEquals("Track Name", savedTrack.getValue().getTrackName());
        assertEquals(3L, savedTrack.getValue().getFilesize());
    }

    @Test
    void acceptsTrackRatingOfTen() throws Exception {
        when(tracks.findById(3L)).thenReturn(Optional.of(new Track()));
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(post("/api/tracks/3/rating")
                        .contentType("application/json")
                        .content("{\"rating\":10}")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(10));

        verify(ratings).recordRating(3L, "192.0.2.1", (short) 10);
    }

    @Test
    void rejectsTrackRatingAboveTen() throws Exception {
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(post("/api/tracks/3/rating")
                        .contentType("application/json")
                        .content("{\"rating\":11}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Rating must be an integer between 1 and 10."));

        verifyNoInteractions(ratings);
    }

    private User user(boolean emailVerified) {
        User user = new User(7L, "listener", "listener@example.com", "password");
        user.setEmailVerified(emailVerified);
        return user;
    }

    private MockMultipartFile trackFile() {
        return new MockMultipartFile(
                "file", "song.reason", "application/octet-stream", new byte[]{1, 2, 3});
    }
}
