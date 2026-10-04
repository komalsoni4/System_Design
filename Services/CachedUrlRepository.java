package Services;

import java.util.LinkedHashMap;
import java.util.Map;

import Entities.ShortUrl;

public class CachedUrlRepository  implements UrlRepository {
    private final UrlRepository database;                 // the real repository we wrap
    private final Map<String, ShortUrl> cache;

    public CachedUrlRepository(UrlRepository database, int capacity) {
        this.database = database;
        // accessOrder = true -> the least recently used entry is the "eldest"
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ShortUrl> eldest) {
                return size() > capacity;                 // cache full -> drop the LRU entry
            }
        };
    }

    @Override
    public synchronized boolean saveIfAbsent(ShortUrl url) {
        boolean saved = database.saveIfAbsent(url);
        if (saved) cache.put(url.getShortKey(), url);
        return saved;
    }

    @Override
    public synchronized ShortUrl findByKey(String shortKey) {
        ShortUrl url = cache.get(shortKey);               // 1. try the cache
        if (url != null) return url;                      //    cache HIT
        url = database.findByKey(shortKey);               // 2. cache MISS -> ask the database
        if (url != null) cache.put(shortKey, url);        // 3. remember it for next time
        return url;
    }

    @Override
    public synchronized void delete(String shortKey) {
        database.delete(shortKey);
        cache.remove(shortKey);                           // never leave a stale entry behind
    }
}
