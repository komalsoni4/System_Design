package Services;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import Entities.User;

public class UserService {
    private final Map<String, User> users = new ConcurrentHashMap<>();

    public User registerUser(String name, String email) {
        User user = new User(UUID.randomUUID().toString(), name, email);
        users.put(user.getUserId(), user);
        return user;
    }

    public User getUser(String userId) {
        User user = users.get(userId);
        if (user == null) throw new IllegalArgumentException("Unknown user: " + userId);
        return user;
    }
}