package cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;

public class TestReadSourceCode {

    @Test
    public void readPut() {
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .build();

        cache.put("key", "value");

        cache.getIfPresent("key");
    }

}
