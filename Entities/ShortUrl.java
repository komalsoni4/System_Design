package Entities;
import java.time.LocalDateTime;

public class ShortUrl {
    private final String shortKey;          // "aZ3k9Qp"  (this is the primary key)
    private final String longUrl;           // the original url
    private final String userId;            // who created it  (links to User)
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;  // null means "never expires"
    private boolean active;                 // false once the owner deletes it

    public ShortUrl(String shortKey, String longUrl, String userId, LocalDateTime expiresAt) {
        this.shortKey = shortKey;
        this.longUrl = longUrl;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public void deactivate() { this.active = false; }

    public String getShortKey() { return shortKey; }
    public String getLongUrl() { return longUrl; }
    public String getUserId() { return userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
}