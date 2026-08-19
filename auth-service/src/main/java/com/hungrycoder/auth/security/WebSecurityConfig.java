package com.hungrycoder.auth.security;

import com.hungrycoder.auth.security.jwt.AuthEntryPointJwt;
import com.hungrycoder.auth.security.jwt.AuthTokenFilter;
import com.hungrycoder.auth.security.jwt.JwtUtils;
import com.hungrycoder.auth.security.services.UserDetailsServiceImpl;
import java.util.List;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration class to set up Spring Security.
 */
@Configuration // Marks the class as a source of bean definitions
@EnableMethodSecurity // Enables method-level security annotations
@EnableConfigurationProperties(value = {JwtConfigProperties.class, AuthorizationConfigProperties.class})
public class WebSecurityConfig {

    public static final List<String> OPEN_AUTH_ENDPOINTS = List.of("/api/v1/auth/signin", "/api/v1/auth/signup");

    private final AuthEntryPointJwt authEntryPointJwt; // Injects the entry point for unauthorized requests

    public WebSecurityConfig(AuthEntryPointJwt authEntryPointJwt) {
        this.authEntryPointJwt = authEntryPointJwt;
    }

    /**
     * Creates a bean for the authentication JWT token filter.
     *
     * @return AuthTokenFilter instance
     */
    @Bean
    public AuthTokenFilter authenticationJwtTokenFilter(JwtUtils jwtUtils, UserDetailsServiceImpl userDetailsService) {
        return new AuthTokenFilter(jwtUtils, userDetailsService); // Returns a new instance of AuthTokenFilter
    }

    /**
     * Creates a bean for the DAO authentication provider.
     *
     * @return DaoAuthenticationProvider instance
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsServiceImpl userDetailsService) {
        DaoAuthenticationProvider authProvider =
                new DaoAuthenticationProvider(); // Create a new authentication provider
        authProvider.setUserDetailsService(userDetailsService); // Set the user details service
        authProvider.setPasswordEncoder(passwordEncoder()); // Set the password encoder
        return authProvider; // Return the configured authentication provider
    }

    /**
     * Creates a bean for the authentication manager.
     *
     * @param authConfig Authentication configuration
     * @return AuthenticationManager instance
     * @throws Exception if there is an error getting the authentication manager
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager(); // Returns the authentication manager from the configuration
    }

    /**
     * Creates a bean for the password encoder.
     *
     * @return PasswordEncoder instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // Returns a new instance of BCryptPasswordEncoder
    }

    /**
     * Configures the security filter chain for HTTP requests.
     *
     * @param http HttpSecurity configuration
     * @return SecurityFilterChain instance
     * @throws Exception if there is an error configuring the security filter chain
     */
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            DaoAuthenticationProvider authenticationProvider,
            AuthTokenFilter authenticationJwtTokenFilter)
            throws Exception {
        // Configure CSRF protection, exception handling, session management, and authorization
        http.csrf(AbstractHttpConfigurer::disable) // Disable CSRF protection
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authEntryPointJwt))
                // Set unauthorized handler
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Set session policy to stateless
                .authorizeHttpRequests(auth -> auth
                        // Permit open-auth-endpoints
                        .requestMatchers(OPEN_AUTH_ENDPOINTS.toArray(new String[0]))
                        .permitAll()
                        // Permit all "/actuator/** endpoints
                        .requestMatchers(EndpointRequest.toAnyEndpoint())
                        .permitAll()
                        // Configure authorization for HTTP requests of all endpoints
                        .anyRequest()
                        .authenticated());
        // Require authentication for any other request
        http.authenticationProvider(authenticationProvider); // Set the authentication provider
        http.addFilterBefore(authenticationJwtTokenFilter, UsernamePasswordAuthenticationFilter.class);
        // Build and return the security filter chain
        return http.build();
    }
}
