package org.reasonstation.repository;

import org.reasonstation.entity.PatchRating;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PatchRatingRepository extends CrudRepository<PatchRating, String> {
    @Modifying
    @Query("""
            INSERT INTO patch_ratings (patchid, ip_address, rating)
            VALUES (:patchid, CAST(:ipAddress AS inet), :rating)
            ON CONFLICT (patchid, ip_address) DO NOTHING
            """)
    int recordRating(@Param("patchid") Long patchid, @Param("ipAddress") String ipAddress,
                     @Param("rating") short rating);
}
