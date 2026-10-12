package org.reasonstation.repository;

import org.reasonstation.entity.Track;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrackRepository extends CrudRepository<Track, Long> {

    @Query("""
            SELECT t.trackid,
                   t.userid,
                   t.artist_alias,
                   t.track_name,
                   t.music_style,
                   t.filename,
                   t.track_uri,
                   t.date_uploaded,
                   AVG(r.rating) AS average_track_rating,
                   COUNT(d.ip_address) AS download_count
            FROM tracks t
            LEFT JOIN track_downloads d ON d.trackid = t.trackid
            LEFT JOIN track_ratings r ON r.trackid = t.trackid
            GROUP BY t.trackid,
                     t.userid,
                     t.artist_alias,
                     t.track_name,
                     t.music_style,
                     t.filename,
                     t.track_uri,
                     t.date_uploaded
            ORDER BY RANDOM()
            LIMIT :limit
            """)
    List<TrackWithRating> findRandomTracks(@Param("limit") int limit);

    @Query("""
            SELECT t.trackid,
                   t.artist_alias,
                   t.track_name,
                   t.music_style,
                   t.filename,
                   COUNT(*) AS download_count
            FROM tracks t
            JOIN track_downloads d ON d.trackid = t.trackid
            WHERE d.date_downloaded >= date_trunc('week', CURRENT_TIMESTAMP)
            GROUP BY t.trackid,
                     t.artist_alias,
                     t.track_name,
                     t.music_style,
                     t.filename
            ORDER BY download_count DESC,
                     t.trackid DESC
            LIMIT :limit
            """)
    List<PopularTrack> findMostDownloadedTracksThisWeek(@Param("limit") int limit);

    @Query("""
            SELECT t.trackid,
                   t.artist_alias,
                   t.track_name,
                   t.music_style,
                   t.filename,
                   AVG(r.rating) AS average_track_rating,
                   COUNT(*) AS rating_count
            FROM tracks t
            JOIN track_ratings r ON r.trackid = t.trackid
            WHERE r.date_rated >= date_trunc('week', CURRENT_TIMESTAMP)
            GROUP BY t.trackid,
                     t.artist_alias,
                     t.track_name,
                     t.music_style,
                     t.filename
            ORDER BY average_track_rating DESC,
                     rating_count DESC,
                     t.trackid DESC
            LIMIT :limit
            """)
    List<HighestRatedTrack> findHighestRatedTracksThisWeek(@Param("limit") int limit);

    @Query("""
            SELECT t.artist_alias,
                   COUNT(*) AS download_count
            FROM tracks t
            JOIN track_downloads d ON d.trackid = t.trackid
            WHERE d.date_downloaded >= date_trunc('month', CURRENT_TIMESTAMP)
            GROUP BY t.artist_alias
            ORDER BY download_count DESC,
                     t.artist_alias
            LIMIT :limit
            """)
    List<PopularArtist> findMostDownloadedArtistsThisMonth(@Param("limit") int limit);

    @Query("""
            SELECT *
            FROM tracks
            ORDER BY date_uploaded DESC,
                     trackid DESC
            LIMIT :limit
            """)
    List<Track> findNewestTracks(@Param("limit") int limit);

    @Query("""
            SELECT artist_alias
            FROM (
                SELECT DISTINCT artist_alias
                FROM tracks
            ) artists
            ORDER BY RANDOM()
            LIMIT :limit
            """)
    List<String> findRandomArtistAliases(@Param("limit") int limit);

    @Query("""
            SELECT t.artist_alias,
                   COUNT(*) AS track_count,
                   u.date_created
            FROM tracks t
            JOIN "user" u ON u.userid = t.userid
            GROUP BY t.artist_alias,
                     u.date_created
            ORDER BY RANDOM()
            LIMIT :limit
            """)
    List<ArtistAliasTrackCount> findRandomArtistAliasesWithTrackCount(@Param("limit") int limit);

    /**
     * Find all tracks for a specific user
     */
    List<Track> findByUserid(Long userid);
    
    /**
     * Find tracks by user and artist alias
     */
    @Query("""
            SELECT *
            FROM tracks
            WHERE userid = :userid
              AND artist_alias = :artistAlias
            """)
    List<Track> findByUseridAndArtistAlias(@Param("userid") Long userid, @Param("artistAlias") String artistAlias);

    @Query("""
            SELECT t.trackid,
                   t.userid,
                   t.artist_alias,
                   t.track_name,
                   t.music_style,
                   t.filename,
                   t.track_uri,
                   t.date_uploaded,
                   AVG(r.rating) AS average_track_rating,
                   COUNT(d.ip_address) AS download_count
            FROM tracks t
            LEFT JOIN track_downloads d ON d.trackid = t.trackid
            LEFT JOIN track_ratings r ON r.trackid = t.trackid
            WHERE t.artist_alias = :artistAlias
            GROUP BY t.trackid,
                     t.userid,
                     t.artist_alias,
                     t.track_name,
                     t.music_style,
                     t.filename,
                     t.track_uri,
                     t.date_uploaded
            ORDER BY t.date_uploaded DESC
            """)
    List<TrackWithRating> findByArtistAlias(@Param("artistAlias") String artistAlias);
    
    /**
     * Find tracks by track name
     */
    @Query("""
            SELECT *
            FROM tracks
            WHERE track_name ILIKE :trackName
            """)
    List<Track> findByTrackNameContains(@Param("trackName") String trackName);

    @Query("""
            SELECT t.trackid,
                   t.userid,
                   t.artist_alias,
                   t.track_name,
                   t.music_style,
                   t.filename,
                   t.track_uri,
                   t.date_uploaded,
                   COUNT(d.ip_address) AS download_count
            FROM tracks t
            LEFT JOIN track_downloads d ON d.trackid = t.trackid
            WHERE t.artist_alias ILIKE CONCAT('%', :searchTerm, '%')
               OR t.track_name ILIKE CONCAT('%', :searchTerm, '%')
            GROUP BY t.trackid,
                     t.userid,
                     t.artist_alias,
                     t.track_name,
                     t.music_style,
                     t.filename,
                     t.track_uri,
                     t.date_uploaded
            ORDER BY t.artist_alias,
                     t.track_name
            """)
    List<Track> searchByArtistAliasOrTrackName(@Param("searchTerm") String searchTerm);
    
    /**
     * Find a track by user and filename
     */
    @Query("""
            SELECT *
            FROM tracks
            WHERE userid = :userid
              AND filename = :filename
            LIMIT 1
            """)
    Optional<Track> findByUseridAndFilename(@Param("userid") Long userid, @Param("filename") String filename);
    
    /**
     * Count tracks for a specific user
     */
    @Query("""
            SELECT COUNT(*)
            FROM tracks
            WHERE userid = :userid
            """)
    long countByUserid(@Param("userid") Long userid);
    
    /**
     * Delete all tracks for a user
     */
    @Query("""
            DELETE FROM tracks
            WHERE userid = :userid
            """)
    void deleteByUserid(@Param("userid") Long userid);

    /**
     * Delete a track only when it belongs to the specified user
     */
    void deleteByTrackidAndUserid(Long trackid, Long userid);
}
