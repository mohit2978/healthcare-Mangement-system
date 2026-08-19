package com.hungrycoder.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtConfigProperties(
        // Inject the JWT secret from application properties
        String secret,
        // Inject the JWT expiration time (in milliseconds) from application properties
        int expiration) {}
