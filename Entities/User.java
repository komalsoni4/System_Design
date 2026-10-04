package Entities;
import java.time.LocalDateTime;

public class User {
    private final String userId;
    private final String name;
    private final String email;
    private final LocalDateTime createdAt;

    public User(String userId, String name, String email) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.createdAt = LocalDateTime.now();
    }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}