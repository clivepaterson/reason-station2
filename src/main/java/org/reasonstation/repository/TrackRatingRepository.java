package org.reasonstation.repository;

import org.reasonstation.entity.TrackRating;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrackRatingRepository extends CrudRepository<TrackRating, String> {
    @Modifying
    @Query("""
            INSERT INTO track_ratings (trackid, ip_address, rating)
            VALUES (:trackid, CAST(:ipAddress AS inet), :rating)
            """)
    void recordRating(@Param("trackid") Long trackid, @Param("ipAddress") String ipAddress,
                      @Param("rating") short rating);
}
