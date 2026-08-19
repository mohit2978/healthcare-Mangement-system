package com.hungrycoder.auth.controllers;

import com.hungrycoder.auth.payload.request.LoginRequest;
import com.hungrycoder.auth.payload.request.SignupRequest;
import com.hungrycoder.auth.services.AuthenticationService;
import com.hungrycoder.auth.services.AuthorizationService;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final AuthorizationService authorizationService;

    public AuthController(AuthenticationService authenticationService, AuthorizationService authorizationService) {
        this.authenticationService = authenticationService;
        this.authorizationService = authorizationService;
    }

    @PostMapping(path = {"", "/"})
    public ResponseEntity<Void> auth(
            Authentication authentication,
            @RequestHeader(name = "X-Original-Method", required = false) String originalMethod,
            @RequestHeader(name = "X-Original-Path", required = false) String originalPath) {
        if (authentication == null || !StringUtils.hasText(originalMethod) || !StringUtils.hasText(originalPath)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Set<String> userRoles = authentication.getAuthorities().stream()
                .filter(Objects::nonNull)
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());

        var isAllowed = authorizationService.isAuthorized(userRoles, originalMethod, originalPath);
        if (!isAllowed) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/signin")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
        return authenticationService.authenticateUser(loginRequest);
    }

    @PostMapping("/signup")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignupRequest signUpRequest) {
        return authenticationService.registerUser(signUpRequest);
    }
}
