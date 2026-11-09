package demo;

import java.util.HashMap;
import java.util.Map;

/** Figure 1(a) of the FineLock paper, plus a few more patterns. */
public class Cache {
    private final Map<String, String> data = new HashMap<>();
    private boolean cacheValid;
    private int hits;

    // downgrading lock (pattern cwer)
    public synchronized String cached(String key) {
        if (!cacheValid) {
            data.put(key, "value-" + key); // write into cache
            cacheValid = true;
        }
        return data.get(key); // read data
    }

    // read lock
    public synchronized int size() {
        return data.size();
    }

    // write lock
    public synchronized void invalidate() {
        cacheValid = false;
    }

    // splitting lock: write, then downgrade to read
    public synchronized int hit() {
        hits++;
        return hits + data.size();
    }
}
