package com.hungrycoder.auth.payload.response;

import java.util.Set;

public record JwtResponse(String token, String type, String id, String username, String email, Set<String> roles) {
    public JwtResponse(String token, String id, String username, String email, Set<String> roles) {
        this(token, "Bearer", id, username, email, roles); // Default value for `type`
    }
}
