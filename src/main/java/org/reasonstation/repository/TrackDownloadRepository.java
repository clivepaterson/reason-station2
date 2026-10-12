package org.reasonstation.repository;

import org.reasonstation.entity.TrackDownload;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrackDownloadRepository extends CrudRepository<TrackDownload, String> {
    @Modifying
    @Query("""
            INSERT INTO track_downloads (trackid, ip_address)
            VALUES (:trackid, CAST(:ipAddress AS inet))
            ON CONFLICT (trackid, ip_address) DO NOTHING
            """)
    void recordDownload(@Param("trackid") Long trackid, @Param("ipAddress") String ipAddress);

    @Query("""
            SELECT COUNT(ip_address)
            FROM track_downloads
            WHERE trackid = :trackid
            """)
    int countByTrackid(@Param("trackid") Long trackid);
}
