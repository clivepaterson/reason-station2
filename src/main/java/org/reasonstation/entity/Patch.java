package org.reasonstation.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("patches")
public class Patch {
    @Id
    private Long patchid;

    @Column("userid")
    private Long userid;

    @Column("artist_alias")
    private String artistAlias;

    @Column("patch_name")
    private String patchName;

    @Column("description")
    private String description;

    @Column("filename")
    private String filename;

    @Column("ext")
    private String ext;

    @Column("filesize")
    private Long filesize;

    @Column("patch_uri")
    private String patchUri;

    @Column("date_uploaded")
    private Instant dateUploaded = Instant.now();

    public Patch() {
    }

    public Patch(Long userid, String artistAlias, String patchName, String description,
                 String filename, String ext, Long filesize, String patchUri) {
        this.userid = userid;
        this.artistAlias = artistAlias;
        this.patchName = patchName;
        this.description = description;
        this.filename = filename;
        this.ext = ext;
        this.filesize = filesize;
        this.patchUri = patchUri;
    }

    public Long getPatchid() {
        return patchid;
    }

    public void setPatchid(Long patchid) {
        this.patchid = patchid;
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

    public String getPatchName() {
        return patchName;
    }

    public void setPatchName(String patchName) {
        this.patchName = patchName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getExt() {
        return ext;
    }

    public void setExt(String ext) {
        this.ext = ext;
    }

    public Long getFilesize() {
        return filesize;
    }

    public void setFilesize(Long filesize) {
        this.filesize = filesize;
    }

    public String getPatchUri() {
        return patchUri;
    }

    public void setPatchUri(String patchUri) {
        this.patchUri = patchUri;
    }

    public Instant getDateUploaded() {
        return dateUploaded;
    }

    public void setDateUploaded(Instant dateUploaded) {
        this.dateUploaded = dateUploaded;
    }
}
