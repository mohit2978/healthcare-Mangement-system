package com.hungrycoder.auth.security.jwt;

import static com.hungrycoder.auth.security.WebSecurityConfig.OPEN_AUTH_ENDPOINTS;

import com.hungrycoder.auth.security.services.UserDetailsServiceImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter to validate the JWT token and set user authentication in the security context.
 */
public class AuthTokenFilter extends OncePerRequestFilter {

    // Automatically inject JwtUtils to handle JWT operations
    private final JwtUtils jwtUtils;
    // Automatically inject UserDetailsServiceImpl to load user details
    private final UserDetailsServiceImpl userDetailsService;

    private static final Logger logger = LoggerFactory.getLogger(AuthTokenFilter.class); // Logger for logging errors

    public AuthTokenFilter(JwtUtils jwtUtils, UserDetailsServiceImpl userDetailsService) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
    }

    /**
     * A custom implementation for skipping the filter for JWT token authentication
     *
     * @param request current HTTP request
     * @return boolean true to NOT filter the request for JWT token authentication, otherwise false
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        var path = request.getRequestURI();
        logger.info("auth-service requested URI:[{}]", path);
        // Skip open-auth-endpoints
        if (OPEN_AUTH_ENDPOINTS.contains(path)) {
            return true;
        }
        // Skip actuator endpoints
        return path.startsWith("/actuator");
    }

    /**
     * Filter method to process the JWT token and set authentication.
     *
     * @param request The HTTP request.
     * @param response The HTTP response.
     * @param filterChain The filter chain for further processing.
     * @throws ServletException If a servlet-related exception occurs.
     * @throws IOException If an input or output exception occurs.
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // Parse and validate the JWT token from the request
            var jwtToken = jwtUtils.parseJwt(request)
                    .orElseThrow(() -> new RuntimeException("Authentication token not valid!"));
            // Check if token is expired
            if (jwtUtils.isTokenExpired(jwtToken)) {
                throw new RuntimeException("Authentication token expired!");
            }
            var username = jwtUtils.extractUsername(jwtToken);
            // Load user details from the username
            var userDetails = userDetailsService.loadUserByUsername(username);
            // Create an authentication token with the user details
            var authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            // Set the authentication in the security context
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception e) {
            // Log any errors that occur during authentication
            logger.error("Cannot set user authentication, due to: {}", e.getMessage(), e);
        }
        // Continue the filter chain
        filterChain.doFilter(request, response);
    }
}
