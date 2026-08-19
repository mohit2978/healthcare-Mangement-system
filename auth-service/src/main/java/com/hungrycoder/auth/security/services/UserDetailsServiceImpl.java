package com.hungrycoder.auth.security.services;

import com.hungrycoder.auth.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of UserDetailsService to load user-specific data.
 */
@Service // Indicates that this class is a service component
public class UserDetailsServiceImpl implements UserDetailsService {

    // Automatically injects UserRepository bean
    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Loads user details by username.
     *
     * @param username The username of the user.
     * @return UserDetails containing user information.
     * @throws UsernameNotFoundException if the user is not found.
     */
    @Override
    @Transactional // Ensures that the method is transactional
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Attempt to find the user by username
        var user = userRepository
                .findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User Not Found with username: " + username));

        // Return UserDetails implementation for the found user
        return UserDetailsImpl.build(user);
    }
}
