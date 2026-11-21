package org.reasonstation.repository;

import org.reasonstation.entity.Patch;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatchRepository extends CrudRepository<Patch, Long> {
    @Modifying
    @Transactional
    @Query("DELETE FROM patches WHERE patchid = :patchid AND userid = :userid")
    void deleteByPatchidAndUserid(@Param("patchid") Long patchid, @Param("userid") Long userid);

    List<Patch> findByUseridOrderByDateUploadedDesc(Long userid);

    List<Patch> findByArtistAliasOrderByDateUploadedDesc(String artistAlias);

    @Query("""
            SELECT p.*, (SELECT AVG(r.rating) FROM patch_ratings r WHERE r.patchid = p.patchid)
                AS average_patch_rating
            FROM patches p WHERE p.userid = :userid ORDER BY p.date_uploaded DESC
            """)
    List<PatchWithRating> findWithRatingsByUserid(Long userid);

    @Query("""
            WITH selected AS (
                SELECT * FROM patches WHERE artist_alias = :artistAlias
                ORDER BY date_uploaded DESC, patchid DESC
                LIMIT :limit OFFSET :offset
            )
            SELECT p.*, (SELECT AVG(r.rating) FROM patch_ratings r WHERE r.patchid = p.patchid)
                AS average_patch_rating
            FROM selected p
            ORDER BY p.date_uploaded DESC, p.patchid DESC
            """)
    List<PatchWithRating> findWithRatingsByArtistAlias(
            @Param("artistAlias") String artistAlias, @Param("limit") int limit, @Param("offset") long offset);

    @Query("SELECT COUNT(*) FROM patches WHERE artist_alias = :artistAlias")
    long countByArtistAlias(@Param("artistAlias") String artistAlias);

    @Query("""
            SELECT p.*, (SELECT AVG(r.rating) FROM patch_ratings r WHERE r.patchid = p.patchid)
                AS average_patch_rating
            FROM patches p WHERE p.patchid = :patchid
            """)
    Optional<PatchWithRating> findWithRatingById(Long patchid);

    @Query("""
            SELECT p.patchid,
                   p.artist_alias,
                   p.patch_name,
                   AVG(r.rating) AS average_patch_rating,
                   COUNT(*) AS rating_count
            FROM patches p
            JOIN patch_ratings r ON r.patchid = p.patchid
            GROUP BY p.patchid,
                     p.artist_alias,
                     p.patch_name
            ORDER BY average_patch_rating DESC,
                     rating_count DESC,
                     p.patchid DESC
            LIMIT :limit
            """)
    List<HighestRatedPatch> findHighestRatedPatches(@Param("limit") int limit);
}
