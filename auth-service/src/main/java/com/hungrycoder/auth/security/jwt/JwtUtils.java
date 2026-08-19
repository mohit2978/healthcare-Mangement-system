package com.hungrycoder.auth.security.jwt;

import static org.springframework.util.StringUtils.hasText;

import com.hungrycoder.auth.security.JwtConfigProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Key;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Utility class for managing JSON Web Tokens (JWT).
 */
@Component // Indicate that this class is a Spring component
public class JwtUtils {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class); // Logger for logging errors

    private final JwtConfigProperties jwtConfigProperties;
    private final Key key;

    public JwtUtils(JwtConfigProperties jwtConfigProperties) {
        this.jwtConfigProperties = jwtConfigProperties;
        // Create a signing key from the JWT secret
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtConfigProperties.secret()));
    }

    /**
     * Generate a JWT token based on the provided authentication.
     *
     * @param username The username of the requested user.
     * @param roles The UserRole of the requested user.
     * @return The generated JWT token as a string.
     */
    public String generateJwtToken(String username, Set<String> roles) {
        logger.debug("Generating JWT token for username[{}] with roles:{}", username, roles);
        // Build and return the JWT token
        return Jwts.builder()
                .setSubject(username) // Set the subject (username)
                .claim("roles", roles)
                .setIssuedAt(new Date()) // Set the issue date
                .setExpiration(
                        new Date((new Date()).getTime() + jwtConfigProperties.expiration())) // Set the expiration date
                .signWith(key, SignatureAlgorithm.HS256) // Sign the token using the secret key and algorithm
                .compact(); // Compact the JWT into a string
    }

    /**
     * Parse the JWT token from the Authorization header.
     *
     * @param request The HTTP request.
     * @return The JWT token if found, or null if not found.
     */
    public Optional<String> parseJwt(HttpServletRequest request) {
        // Get the Authorization header from the request
        var headerAuth = request.getHeader(HttpHeaders.AUTHORIZATION);
        // Check if the header is valid and starts with "Bearer "
        if (hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            // Extract the JWT token from the header
            var jwtToken = headerAuth.substring(7);
            return Optional.of(jwtToken);
        }
        return Optional.empty(); // Return Optional.empty() if no valid token is found
    }

    /**
     * Validate expiry of the given JWT token.
     *
     * @param token The JWT token to validate.
     * @return true if the token is expired, false otherwise.
     */
    public boolean isTokenExpired(String token) {
        return extractAllClaims(token)
                .map(Claims::getExpiration)
                .orElseThrow(() -> new RuntimeException("Could not extract Expiration"))
                .before(new Date());
    }

    /**
     * Extract the username from the given JWT token.
     *
     * @param token The JWT token.
     * @return The username extracted from the token.
     */
    public String extractUsername(String token) {
        return extractAllClaims(token)
                .map(Claims::getSubject)
                .orElseThrow(() -> new RuntimeException("Could not extract username!"));
    }

    private Optional<Claims> extractAllClaims(String token) {
        try {
            var claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return Optional.of(claims);
        } catch (MalformedJwtException e) {
            logger.error("Invalid JWT token: {}", e.getMessage(), e); // Log invalid token error
        } catch (ExpiredJwtException e) {
            logger.error("JWT token is expired: {}", e.getMessage(), e); // Log expired token error
        } catch (UnsupportedJwtException e) {
            logger.error("JWT token is unsupported: {}", e.getMessage(), e); // Log unsupported token error
        } catch (IllegalArgumentException e) {
            logger.error("JWT claims string is empty: {}", e.getMessage(), e); // Log empty claims error
        }
        return Optional.empty();
    }
}
