package Entities;
public class User {
    private String UserId;
    private String name;
    private String email;

    public User(String UserId, String name, String email) {
        this.UserId = UserId;
        this.name = name;
        this.email = email;
    }

    public String getUserId() {
        return UserId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}