package com.hungrycoder.auth.services;

import com.hungrycoder.auth.models.Role;
import com.hungrycoder.auth.models.User;
import com.hungrycoder.auth.models.UserRole;
import com.hungrycoder.auth.payload.request.LoginRequest;
import com.hungrycoder.auth.payload.request.SignupRequest;
import com.hungrycoder.auth.payload.response.JwtResponse;
import com.hungrycoder.auth.payload.response.MessageResponse;
import com.hungrycoder.auth.repository.RoleRepository;
import com.hungrycoder.auth.repository.UserRepository;
import com.hungrycoder.auth.security.jwt.JwtUtils;
import com.hungrycoder.auth.security.services.UserDetailsImpl;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder encoder;
    private final JwtUtils jwtUtils;

    public AuthenticationService(
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder encoder,
            JwtUtils jwtUtils) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.encoder = encoder;
        this.jwtUtils = jwtUtils;
    }

    /**
     * Authenticate a user and return a JWT token with user details.
     *
     * @param loginRequest The login request containing username and password.
     * @return A JwtResponse containing the JWT token and user details.
     */
    public ResponseEntity<JwtResponse> authenticateUser(LoginRequest loginRequest) {
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.username(), loginRequest.password()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        if (authentication.getPrincipal() instanceof UserDetailsImpl userDetails) {
            var roles = userDetails.getAuthorities().stream()
                    .filter(Objects::nonNull)
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
            var username = userDetails.getUsername();
            var jwt = jwtUtils.generateJwtToken(username, roles);
            // Return the JWT response
            var jwtResponse = new JwtResponse(jwt, userDetails.getId(), username, userDetails.getEmail(), roles);
            return ResponseEntity.ok().body(jwtResponse);
        }
        return ResponseEntity.badRequest().build();
    }

    /**
     * Register a new user account.
     *
     * @param signUpRequest The signup request containing user details.
     * @return A MessageResponse indicating success or error.
     */
    public ResponseEntity<MessageResponse> registerUser(SignupRequest signUpRequest) {
        var username = signUpRequest.username();
        if (userRepository.existsByUsername(username)) {
            ResponseEntity.badRequest().body(new MessageResponse("Error: Username is already taken!"));
        }
        var email = signUpRequest.email();
        if (userRepository.existsByEmail(email)) {
            ResponseEntity.badRequest().body(new MessageResponse("Error: Email is already in use!"));
        }
        var roles = signUpRequest.roles().stream()
                .filter(Objects::nonNull)
                .map(this::toRole)
                .collect(Collectors.toSet());
        log.debug("Saving user with username:[{}], email:[{}] and roles:{}", username, email, roles);
        var user = new User(username, email, encoder.encode(signUpRequest.password()), roles);
        userRepository.save(user); // Save new user

        var response = new MessageResponse("User registered successfully!");
        return ResponseEntity.ok().body(response);
    }

    @NonNull
    private Role toRole(UserRole userRole) {
        return switch (userRole) {
            case ADMIN ->
                roleRepository
                        .findByName(UserRole.ADMIN)
                        .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
            case DOCTOR ->
                roleRepository
                        .findByName(UserRole.DOCTOR)
                        .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
            case PATIENT ->
                roleRepository
                        .findByName(UserRole.PATIENT)
                        .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
        };
    }
}
