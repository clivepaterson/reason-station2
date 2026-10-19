package org.reasonstation.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("track_ratings")
public class TrackRating {
    @Column("trackid")
    private Long trackid;

    @Id
    @Column("ip_address")
    private String ipAddress;

    @Column("rating")
    private Short rating;

    @Column("date_rated")
    private Instant dateRated;

    public TrackRating() {
    }
}
