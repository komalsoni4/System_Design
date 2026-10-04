package Services;

import java.security.SecureRandom;

public class RandomGenerator implements KeyGenerator {
    private static final String CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private final SecureRandom random = new SecureRandom();

    @Override
    public String getNextKey() {
        String key = "";
        for (int i = 0; i < 7; i++) {
            key = key + CHARS.charAt(random.nextInt(62));
        }
        return key;
    }
    
}
