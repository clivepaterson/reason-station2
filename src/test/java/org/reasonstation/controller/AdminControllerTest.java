package org.reasonstation.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {
    @Mock
    private UserRepository users;

    @Test
    void listsOneHundredUsersPerPageNewestFirstWithStableOrdering() {
        User user = new User(7L, "listener", "listener@example.com", "secret-hash");
        user.setAdmin(true);
        var pageable = PageRequest.of(1, 100,
                Sort.by(Sort.Order.desc("dateCreated"), Sort.Order.asc("userid")));
        when(users.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 101));

        var response = new AdminController(users).users(2);
        var result = response.getBody();

        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(2, result.page());
        assertEquals(100, result.pageSize());
        assertEquals(101, result.totalElements());
        assertEquals(2, result.totalPages());
        assertEquals(new AdminController.AdminUser(7L, "listener", "listener@example.com",
                false, true, user.getDateCreated()), result.items().getFirst());
        assertEquals(List.of("userid", "alias", "email", "emailVerified", "admin", "dateCreated"),
                java.util.Arrays.stream(AdminController.AdminUser.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test
    void returnsEmptyPageForEmptyDatabase() {
        var pageable = PageRequest.of(0, 100,
                Sort.by(Sort.Order.desc("dateCreated"), Sort.Order.asc("userid")));
        when(users.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        var result = new AdminController(users).users(1).getBody();

        assertEquals(List.of(), result.items());
        assertEquals(0, result.totalElements());
        assertEquals(0, result.totalPages());
    }

    @Test
    void rejectsInvalidPageNumbers() {
        for (int page : new int[]{0, -1, Integer.MIN_VALUE}) {
            var exception = assertThrows(ResponseStatusException.class,
                    () -> new AdminController(users).users(page));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        }
        verifyNoInteractions(users);
    }

    @Test
    void newUsersAreNotAdmins() {
        assertFalse(new User("listener", "listener@example.com", "hash").isAdmin());
    }
}
