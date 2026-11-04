package org.reasonstation.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("patch_ratings")
public class PatchRating {
    @Column("patchid")
    private Long patchid;

    @Id
    @Column("ip_address")
    private String ipAddress;

    @Column("rating")
    private Short rating;

    @Column("date_rated")
    private Instant dateRated;

    public PatchRating() {
    }
}
