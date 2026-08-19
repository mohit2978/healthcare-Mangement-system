package com.hungrycoder.auth.services;

import com.hungrycoder.auth.security.AuthorizationConfigProperties;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

@Service
public class AuthorizationService {

    private static final String BUSINESS_API_PREFIX = "/api/v1/";

    private final AuthorizationConfigProperties authorizationConfigProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public AuthorizationService(AuthorizationConfigProperties authorizationConfigProperties) {
        this.authorizationConfigProperties = authorizationConfigProperties;
    }

    public boolean isAuthorized(Set<String> userRoles, String method, String path) {
        if (userRoles == null || userRoles.isEmpty() || method == null || path == null) {
            return false;
        }

        // Keep infra endpoints out of RBAC enforcement scope.
        if (!path.startsWith(BUSINESS_API_PREFIX)) {
            return true;
        }

        var normalizedMethod = method.toUpperCase(Locale.ROOT);
        var rolePolicies = authorizationConfigProperties.rolePolicies();
        if (rolePolicies == null || rolePolicies.isEmpty()) {
            return false;
        }

        return rolePolicies.stream()
                .filter(Objects::nonNull)
                .filter(policy -> policy.role() != null
                        && userRoles.contains(policy.role().toUpperCase(Locale.ROOT)))
                .anyMatch(policy -> isMethodAndPathAllowed(policy, normalizedMethod, path));
    }

    private boolean isMethodAndPathAllowed(
            AuthorizationConfigProperties.RolePolicy policy, String normalizedMethod, String path) {
        var methods = policy.methods();
        var paths = policy.paths();
        if (methods == null || paths == null || methods.isEmpty() || paths.isEmpty()) {
            return false;
        }

        var methodAllowed = methods.stream()
                .filter(Objects::nonNull)
                .map(m -> m.toUpperCase(Locale.ROOT))
                .anyMatch(normalizedMethod::equals);
        if (!methodAllowed) {
            return false;
        }

        return paths.stream().filter(Objects::nonNull).anyMatch(pattern -> antPathMatcher.match(pattern, path));
    }
}
