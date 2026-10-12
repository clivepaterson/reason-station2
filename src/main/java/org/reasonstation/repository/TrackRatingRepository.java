package org.reasonstation.repository;

import org.reasonstation.entity.TrackRating;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrackRatingRepository extends CrudRepository<TrackRating, Long> {
    @Modifying
    @Query("""
            INSERT INTO track_ratings (trackid, userid, rating)
            VALUES (:trackid, :userid, :rating)
            ON CONFLICT (trackid, userid)
            DO UPDATE SET
                rating = EXCLUDED.rating,
                date_rated = CURRENT_TIMESTAMP
            """)
    void upsertRating(@Param("trackid") Long trackid, @Param("userid") Long userid,
                      @Param("rating") short rating);
}
