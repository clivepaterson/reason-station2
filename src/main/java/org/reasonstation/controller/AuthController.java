package org.reasonstation.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.VerificationEmailService;
import org.reasonstation.service.VerificationEmailService.VerificationEmailException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
public class AuthController {
    private static final String EMAIL_PATTERN =
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final VerificationEmailService verificationEmails;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder,
                          VerificationEmailService verificationEmails) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.verificationEmails = verificationEmails;
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

        User user = users.save(new User(alias, email, passwordEncoder.encode(password)));
        try {
            verificationEmails.send(user);
        } catch (VerificationEmailException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error",
                            "Account created, but the verification email could not be sent. Sign in and resend it."));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "Account created. Check your email to verify it."));
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

    @GetMapping("/api/verify-email")
    public ResponseEntity<Void> verifyEmailLink(
            @RequestParam("verifyEmailToken") String verifyEmailToken) {
        ResponseEntity<Map<String, String>> result =
                verifyEmail(new VerifyEmailRequest(verifyEmailToken));
        String location = result.getStatusCode().is2xxSuccessful()
                ? "/index.html?emailVerified=true"
                : "/index.html?emailVerification=invalid";
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    @PostMapping("/api/resend-verification-email")
    public ResponseEntity<Map<String, String>> resendVerificationEmail(Authentication authentication) {
        User user = users.findByAlias(authentication.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "The authenticated user was not found."));
        }
        if (user.isEmailVerified()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This email address is already verified."));
        }

        user.setVerifyEmailToken(UUID.randomUUID());
        users.save(user);
        try {
            verificationEmails.send(user);
        } catch (VerificationEmailException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "The verification email could not be sent. Try again later."));
        }
        return ResponseEntity.ok(Map.of("message", "Verification email sent."));
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authenticated", true);
        String username;
        if (principal instanceof OidcUser oidcUser) {
            username = authentication.getName();
            if (oidcUser.getEmail() != null) {
                response.put("email", oidcUser.getEmail());
            }
            if (oidcUser.getFullName() != null) {
                response.put("name", oidcUser.getFullName());
            }
        } else if (principal instanceof JwtAuthenticationToken token) {
            username = token.getToken().getClaimAsString("preferred_username");
            if (username == null || username.isBlank()) {
                username = authentication.getName();
            }
            response.put("subject", token.getToken().getSubject());
            response.put("scopes", token.getAuthorities().stream().map(Object::toString).toList());
        } else if (principal instanceof UserDetails user) {
            username = user.getUsername();
        } else {
            username = authentication.getName();
        }
        response.put("username", username);
        response.put("emailVerified", users.findByAlias(username)
                .map(User::isEmailVerified)
                .orElse(false));
        return response;
    }

    @GetMapping("/api/hello")
    public Map<String, String> protectedHello() {
        return Map.of("message", "Hello from the protected ReasonStation API");
    }
}
