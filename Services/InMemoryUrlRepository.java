package Services;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import Entities.ShortUrl;

public class InMemoryUrlRepository implements UrlRepository {
    private final Map<String, ShortUrl> table = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(ShortUrl url) {
        // putIfAbsent = check + insert as ONE step. Returns null if it saved.
        return table.putIfAbsent(url.getShortKey(), url) == null;
    }

    @Override
    public ShortUrl findByKey(String shortKey) {
        return table.get(shortKey);
    }

    @Override
    public void delete(String shortKey) {
        table.remove(shortKey);
    }
}
