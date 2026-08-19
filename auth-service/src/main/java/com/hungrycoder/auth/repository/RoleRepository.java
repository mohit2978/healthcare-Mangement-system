package com.hungrycoder.auth.repository;

import com.hungrycoder.auth.models.Role;
import com.hungrycoder.auth.models.UserRole;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository interface for accessing Role entities in the MongoDB database.
 * It extends MongoRepository, providing CRUD operations for Role objects.
 */
public interface RoleRepository extends MongoRepository<Role, String> {

    /**
     * Find a Role by its name.
     *
     * @param name The name of the role represented as an UserRole enum.
     * @return An Optional containing the Role if found, or empty if not found.
     */
    Optional<Role> findByName(UserRole name);
}
