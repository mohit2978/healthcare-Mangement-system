package com.hungrycoder.auth.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "authorization")
public record AuthorizationConfigProperties(List<RolePolicy> rolePolicies) {

    public record RolePolicy(String role, List<String> methods, List<String> paths) {}
}
