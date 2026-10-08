package org.reasonstation.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("tracks")
public class Track {
    @Id
    private Long trackid;
    
    @Column("userid")
    private Long userid;
    
    @Column("artist_alias")
    private String artistAlias;
    
    @Column("track_name")
    private String trackName;

    @Column("music_style")
    private String musicStyle;
    
    @Column("filename")
    private String filename;
    
    @Column("track_uri")
    private String trackUri;

    @Column("date_uploaded")
    private Instant dateUploaded = Instant.now();

    // Constructors
    public Track() {
    }

    public Track(Long userid, String artistAlias, String trackName, String filename, String trackUri) {
        this.userid = userid;
        this.artistAlias = artistAlias;
        this.trackName = trackName;
        this.filename = filename;
        this.trackUri = trackUri;
    }

    public Track(Long userid, String artistAlias, String trackName, String musicStyle,
                 String filename, String trackUri) {
        this(userid, artistAlias, trackName, filename, trackUri);
        this.musicStyle = musicStyle;
    }

    public Track(Long trackid, Long userid, String artistAlias, String trackName, String filename, String trackUri) {
        this.trackid = trackid;
        this.userid = userid;
        this.artistAlias = artistAlias;
        this.trackName = trackName;
        this.filename = filename;
        this.trackUri = trackUri;
    }

    // Getters and Setters
    public Long getTrackid() {
        return trackid;
    }

    public void setTrackid(Long trackid) {
        this.trackid = trackid;
    }

    public Long getUserid() {
        return userid;
    }

    public void setUserid(Long userid) {
        this.userid = userid;
    }

    public String getArtistAlias() {
        return artistAlias;
    }

    public void setArtistAlias(String artistAlias) {
        this.artistAlias = artistAlias;
    }

    public String getTrackName() {
        return trackName;
    }

    public void setTrackName(String trackName) {
        this.trackName = trackName;
    }

    public String getMusicStyle() {
        return musicStyle;
    }

    public void setMusicStyle(String musicStyle) {
        this.musicStyle = musicStyle;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getTrackUri() {
        return trackUri;
    }

    public void setTrackUri(String trackUri) {
        this.trackUri = trackUri;
    }

    public Instant getDateUploaded() {
        return dateUploaded;
    }

    public void setDateUploaded(Instant dateUploaded) {
        this.dateUploaded = dateUploaded;
    }

    @Override
    public String toString() {
        return "Track{" +
                "trackid=" + trackid +
                ", userid=" + userid +
                ", artistAlias='" + artistAlias + '\'' +
                ", trackName='" + trackName + '\'' +
                ", filename='" + filename + '\'' +
                '}';
    }
}
