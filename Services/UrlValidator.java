package Services;

public class UrlValidator {
     public static void validate(String longUrl) throws Exception {
        if (longUrl == null || longUrl.isBlank()) {
            throw new Exception("Url is empty");
        }
        if (!(longUrl.startsWith("http://") || longUrl.startsWith("https://"))) {
            throw new Exception("Only http/https urls allowed");
        }
        if (longUrl.length() > 2048) {
            throw new Exception("Url too long");
        }
    }
}
