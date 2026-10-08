package org.reasonstation.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("user")
public class User {
    @Id
    private Long userid;
    
    @Column("alias")
    private String alias;
    
    @Column("email")
    private String email;
    
    @Column("password_hash")
    private String passwordHash;

    @Column("email_verified")
    private boolean emailVerified = false;

    @Column("verify_email_token")
    private UUID verifyEmailToken;

    @Column("date_created")
    private Instant dateCreated;

    // Constructors
    public User() {
    }

    public User(String alias, String email, String passwordHash) {
        this.alias = alias;
        this.email = email;
        this.passwordHash = passwordHash;
        this.verifyEmailToken = UUID.randomUUID();
    }

    public User(Long userid, String alias, String email, String passwordHash) {
        this.userid = userid;
        this.alias = alias;
        this.email = email;
        this.passwordHash = passwordHash;
        this.verifyEmailToken = UUID.randomUUID();
    }

    // Getters and Setters
    public Long getUserid() {
        return userid;
    }

    public void setUserid(Long userid) {
        this.userid = userid;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public UUID getVerifyEmailToken() {
        return verifyEmailToken;
    }

    public void setVerifyEmailToken(UUID verifyEmailToken) {
        this.verifyEmailToken = verifyEmailToken;
    }

    public Instant getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Instant dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return "User{" +
                "userid=" + userid +
                ", alias='" + alias + '\'' +
                ", email='" + email + '\'' +
                '}';
    }
}
