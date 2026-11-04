package org.reasonstation.service;

import jakarta.servlet.http.HttpSession;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class SocialAccountService {
    public static final String REGISTRATION_ALIAS_SESSION_ATTRIBUTE =
            SocialAccountService.class.getName() + ".registrationAlias";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final OidcUserService oidcDelegate = new OidcUserService();

    public SocialAccountService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public OidcUser loadOidcUser(OidcUserRequest request) {
        OidcUser user = oidcDelegate.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();
        if (!isTrue(user.getClaims().get("email_verified"))) {
            throw new OAuth2AuthenticationException(
                    "The " + provider + " account did not verify its email address.");
        }
        String email = requiredEmail(user.getEmail(), provider);
        String registrationAlias = takeRegistrationAlias();
        String alias = registrationAlias == null
                ? firstNonBlank(user.getPreferredUsername(), user.getFullName(),
                        email.substring(0, email.indexOf('@')))
                : registrationAlias;
        User account = provision(email, alias, registrationAlias != null);
        return new ProvisionedOidcUser(user, account.getAlias());
    }

    User provision(String rawEmail, String rawAlias, boolean exactAliasRequired) {
        String email = requiredEmail(rawEmail, "social");
        User existing = users.findByEmail(email).orElse(null);
        if (existing != null) {
            if (!existing.isEmailVerified()) {
                existing.setEmailVerified(true);
                existing.setVerifyEmailToken(null);
                users.save(existing);
            }
            return existing;
        }

        String alias = exactAliasRequired ? requiredAvailableAlias(rawAlias) : uniqueAlias(rawAlias);
        User account = new User(alias, email, passwordEncoder.encode(UUID.randomUUID().toString()));
        account.setEmailVerified(true);
        account.setVerifyEmailToken(null);
        return users.save(account);
    }

    private String takeRegistrationAlias() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpSession session = attributes.getRequest().getSession(false);
        if (session == null) {
            return null;
        }
        Object alias = session.getAttribute(REGISTRATION_ALIAS_SESSION_ATTRIBUTE);
        session.removeAttribute(REGISTRATION_ALIAS_SESSION_ATTRIBUTE);
        return alias instanceof String value ? value : null;
    }

    private String requiredAvailableAlias(String rawAlias) {
        String alias = rawAlias == null ? "" : rawAlias.trim();
        if (!alias.matches("[A-Za-z0-9_]{3,30}")) {
            throw new OAuth2AuthenticationException("The selected alias is invalid.");
        }
        if (users.findByAlias(alias).isPresent()) {
            throw new OAuth2AuthenticationException("The selected alias is already in use.");
        }
        return alias;
    }

    private String uniqueAlias(String rawAlias) {
        String base = rawAlias == null ? "user" : rawAlias.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.length() < 3) {
            base = "user";
        }
        base = base.substring(0, Math.min(base.length(), 30));
        String alias = base;
        int suffix = 1;
        while (users.findByAlias(alias).isPresent()) {
            String suffixText = "_" + suffix++;
            alias = base.substring(0, Math.min(base.length(), 30 - suffixText.length())) + suffixText;
        }
        return alias;
    }

    private String requiredEmail(String email, String provider) {
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(
                    "The " + provider + " account did not provide an email address.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "user";
    }

    private static final class ProvisionedOidcUser implements OidcUser {
        private final OidcUser delegate;
        private final String alias;

        private ProvisionedOidcUser(OidcUser delegate, String alias) {
            this.delegate = delegate;
            this.alias = alias;
        }

        @Override
        public Map<String, Object> getClaims() {
            return delegate.getClaims();
        }

        @Override
        public OidcUserInfo getUserInfo() {
            return delegate.getUserInfo();
        }

        @Override
        public OidcIdToken getIdToken() {
            return delegate.getIdToken();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return delegate.getAuthorities();
        }

        @Override
        public String getName() {
            return alias;
        }
    }
}
