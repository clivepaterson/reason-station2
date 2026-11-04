package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseUserDetailsServiceTest {
    @Mock
    private UserRepository users;

    @Test
    void loadsUserByNormalizedEmailAndPreservesAliasAsPrincipalName() {
        User user = new User(7L, "listener", "listener@example.com", "password");
        when(users.findByEmail("listener@example.com")).thenReturn(Optional.of(user));

        var userDetails = new DatabaseUserDetailsService(users)
                .loadUserByUsername(" Listener@Example.com ");

        assertEquals("listener", userDetails.getUsername());
        verify(users).findByEmail("listener@example.com");
    }

    @Test
    void doesNotAcceptAliasAsLoginIdentifier() {
        when(users.findByEmail("listener")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class,
                () -> new DatabaseUserDetailsService(users).loadUserByUsername("listener"));
    }
}
