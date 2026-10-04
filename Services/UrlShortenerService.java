package Services;

import java.time.LocalDateTime;
import java.util.UUID;

import Entities.ClickEvent;
import Entities.ShortUrl;

public class UrlShortenerService {
     private static final String DOMAIN = "https://sho.rt/";
    private final KeyGenerator keyGenerator;                         // Strategy
    private final UrlRepository urlRepository;                       // Repository (maybe Decorated)
    private final UserService userService;

    public UrlShortenerService(KeyGenerator keyGenerator, UrlRepository urlRepository, UserService userService) {
        this.keyGenerator = keyGenerator;
        this.urlRepository = urlRepository;
        this.userService = userService;
    }

    // ---------- FEATURE 1: create a short url ----------
    public String shorten(String userId, String longUrl, String customAlias, LocalDateTime expiresAt) throws Exception {
        userService.getUser(userId);          // 1. user must exist
        UrlValidator.validate(longUrl);       // 2. url must be valid

        if (customAlias != null) {            // 3a. user chose their own key
            ShortUrl url = new ShortUrl(customAlias, longUrl, userId, expiresAt);
            if (!urlRepository.saveIfAbsent(url)) {
                throw new Exception(customAlias);
            }
            return DOMAIN + customAlias;
        }

        for (int attempt = 1; attempt <= 5; attempt++) {   // 3b. we generate the key
            String key = keyGenerator.getNextKey();
            ShortUrl url = new ShortUrl(key, longUrl, userId, expiresAt);
            if (urlRepository.saveIfAbsent(url)) {
                return DOMAIN + key;          // saved, the key is ours
            }
            // key was taken (can happen with random / alias clash) -> try again
        }
        throw new IllegalStateException("Could not generate a unique key"); 
    }

    // ---------- FEATURE 2: redirect ----------
    public String redirect(String shortKey, String country, String device, String referrer) throws Exception {
        ShortUrl url = urlRepository.findByKey(shortKey);   // 1. find it (cache first, then DB)
        if (url == null) {
            throw new Exception(shortKey);       // 2. unknown key -> 404
        }
        if (!url.isActive() || url.isExpired()) {
            throw new Exception(shortKey);        // 3. deleted or expired -> 410
        }

        // 4. record the click event (async in real life)
        return url.getLongUrl();                            // 5. caller sends the HTTP 302
    }

     // ---------- FEATURE 3: delete (only the owner) ----------
    public void deleteUrl(String userId, String shortKey) throws Exception {
        ShortUrl url = urlRepository.findByKey(shortKey);
        if (url == null) throw new Exception(shortKey);
        if (!url.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Only the owner can delete this url");
        }
        url.deactivate();
        urlRepository.delete(shortKey);
    }
}
