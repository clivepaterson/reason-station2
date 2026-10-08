package org.reasonstation.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
public class AuthController {
    private static final String EMAIL_PATTERN =
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/login")
    public ResponseEntity<Void> loginPage() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/index.html?login=true")
                .build();
    }

    @PostMapping("/api/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
        String alias = request.userid() == null ? "" : request.userid().trim();
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        String password = request.password() == null ? "" : request.password();

        if (!alias.matches("[A-Za-z0-9_]{3,30}")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "User ID must be 3-30 characters and use only letters, numbers, or underscores."));
        }
        if (email.length() > 254 || !email.matches(EMAIL_PATTERN)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Please enter a valid email address."));
        }
        if (password.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Password must be at least 8 characters."));
        }
        if (users.findByAlias(alias).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "That user ID is already in use."));
        }
        if (users.findByEmail(email).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "That email address is already in use."));
        }

        users.save(new User(alias, email, passwordEncoder.encode(password)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "Account created successfully."));
    }

    public record RegisterRequest(String userid, String email, String password) {
    }

    @PostMapping("/api/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestBody VerifyEmailRequest request) {
        if (request == null || request.verifyEmailToken() == null
                || request.verifyEmailToken().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A verification request ID is required."));
        }

        UUID requestId;
        try {
            requestId = UUID.fromString(request.verifyEmailToken().trim());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "The verification request ID is invalid."));
        }

        User user = users.findByVerifyEmailToken(requestId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "The verification request was not found."));
        }

        user.setEmailVerified(true);
        user.setVerifyEmailToken(null);
        users.save(user);
        return ResponseEntity.ok(Map.of("message", "Email verified successfully."));
    }

    public record VerifyEmailRequest(String verifyEmailToken) {
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcUser oidcUser) {
            return Map.of(
                    "authenticated", true,
                    "username", oidcUser.getPreferredUsername(),
                    "email", oidcUser.getEmail(),
                    "name", oidcUser.getFullName());
        }
        if (principal instanceof JwtAuthenticationToken token) {
            return Map.of(
                    "authenticated", true,
                    "username", token.getToken().getClaimAsString("preferred_username"),
                    "subject", token.getToken().getSubject(),
                    "scopes", token.getAuthorities().stream().map(Object::toString).toList());
        }
        if (principal instanceof UserDetails user) {
            return Map.of(
                    "authenticated", true,
                    "username", user.getUsername());
        }
        return Map.of("authenticated", true, "username", principal.toString());
    }

    @GetMapping("/api/hello")
    public Map<String, String> protectedHello() {
        return Map.of("message", "Hello from the protected ReasonStation API");
    }
}
