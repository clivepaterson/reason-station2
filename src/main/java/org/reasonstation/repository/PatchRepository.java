package org.reasonstation.repository;

import org.reasonstation.entity.Patch;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PatchRepository extends CrudRepository<Patch, Long> {
    List<Patch> findByUseridOrderByDateUploadedDesc(Long userid);

    List<Patch> findByArtistAliasOrderByDateUploadedDesc(String artistAlias);

    @Query("""
            SELECT p.*, (SELECT AVG(r.rating) FROM patch_ratings r WHERE r.patchid = p.patchid)
                AS average_patch_rating
            FROM patches p WHERE p.userid = :userid ORDER BY p.date_uploaded DESC
            """)
    List<PatchWithRating> findWithRatingsByUserid(Long userid);

    @Query("""
            SELECT p.*, (SELECT AVG(r.rating) FROM patch_ratings r WHERE r.patchid = p.patchid)
                AS average_patch_rating
            FROM patches p WHERE p.artist_alias = :artistAlias ORDER BY p.date_uploaded DESC
            """)
    List<PatchWithRating> findWithRatingsByArtistAlias(String artistAlias);
}
