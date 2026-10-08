package org.reasonstation.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("track_downloads")
public class TrackDownload {
    @Column("trackid")
    private Long trackid;

    @Id
    @Column("ip_address")
    private String ipAddress;

    @Column("date_downloaded")
    private Instant dateDownloaded = Instant.now();

    public TrackDownload() {
    }

    public TrackDownload(Long trackid, String ipAddress) {
        this.trackid = trackid;
        this.ipAddress = ipAddress;
    }

    public Long getTrackid() {
        return trackid;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getDateDownloaded() {
        return dateDownloaded;
    }
}
