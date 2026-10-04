import Entities.User;
import Services.CachedUrlRepository;
import Services.KeyGenerator;
import Services.KeyGeneratorFactory;
import Services.KeyRangeAllocator;
import Services.UrlRepository;
import Services.UserService;
import Services.InMemoryUrlRepository;
import Services.UrlShortenerService;

public class Main {
    public static void main(String[] args) throws Exception {

        KeyRangeAllocator allocator = new KeyRangeAllocator(1000);
        KeyGenerator keyGenerator = KeyGeneratorFactory.create("RANGE", allocator);   // Factory + Strategy
        UrlRepository repository = new CachedUrlRepository(new InMemoryUrlRepository(), 100); // Decorator
        UserService userService = new UserService();
        UrlShortenerService service = new UrlShortenerService(keyGenerator, repository, userService);

         // ---- Test 1: create a user and a short url ----
        User komal = userService.registerUser("Komal", "komal@example.com");
        String shortUrl = service.shorten(komal.getUserId(),
                "https://www.example.com/products/electronics/phones?id=98213", null, null);
        System.out.println("1. Short url      : " + shortUrl);

         // ---- Test 2: redirect (also fires the click event) ----
        String key = shortUrl.substring(shortUrl.lastIndexOf('/') + 1);
        System.out.println("2. Redirects to   : " + service.redirect(key, "IN", "mobile", "google.com"));
        service.redirect(key, "IN", "desktop", "twitter.com");
        service.redirect(key, "US", "mobile", "direct");
        

    }
}
