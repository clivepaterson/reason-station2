package org.reasonstation.repository;

import org.reasonstation.entity.User;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserRepository extends CrudRepository<User, Long> {

    @Query("""
            SELECT *
            FROM "user"
            ORDER BY RANDOM()
            LIMIT :limit
            """)
    List<User> findRandomUsers(@Param("limit") int limit);
    
    /**
     * Find a user by alias
     */
    Optional<User> findByAlias(String alias);
    
    /**
     * Find a user by email
     */
    Optional<User> findByEmail(String email);

    Optional<User> findByVerifyEmailToken(UUID verifyEmailToken);
    
    /**
     * Check if a user exists by alias
     */
    @Query("""
            SELECT COUNT(*) > 0
            FROM "user"
            WHERE alias = :alias
            """)
    boolean existsByAlias(@Param("alias") String alias);
    
    /**
     * Check if a user exists by email
     */
    @Query("""
            SELECT COUNT(*) > 0
            FROM "user"
            WHERE email = :email
            """)
    boolean existsByEmail(@Param("email") String email);
}
