package Services;

import Entities.ShortUrl;

public interface UrlRepository {
     boolean saveIfAbsent(ShortUrl url);    // atomic conditional insert: false if key already exists
    ShortUrl findByKey(String shortKey);   // null if not found
    void delete(String shortKey);
}
