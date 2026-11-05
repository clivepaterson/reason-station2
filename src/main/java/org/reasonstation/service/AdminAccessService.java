package org.reasonstation.service;

import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class AdminAccessService {
    private final UserRepository users;

    public AdminAccessService(UserRepository users) {
        this.users = users;
    }

    public boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        String alias = authentication.getName();
        if (authentication instanceof JwtAuthenticationToken token) {
            String preferredUsername = token.getToken().getClaimAsString("preferred_username");
            if (preferredUsername != null && !preferredUsername.isBlank()) {
                alias = preferredUsername;
            }
        }
        return users.findByAlias(alias).map(User::isAdmin).orElse(false);
    }
}
