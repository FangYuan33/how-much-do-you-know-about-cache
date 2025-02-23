package cache.guava;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class TestGuavaCache {

    @Test
    public void testGet() {
        LoadingCache<String, String> cache = initial();
        cache.put("key1", "value1");

        try {
            System.out.println(cache.get("key"));
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

        System.out.println(cache.getUnchecked("key"));

        String key = "key";
        String value = null;
        try {
            // 在 build 中配置 CacheLoader 再指定 Callable 后者不生效
            value = cache.get(key, key::toUpperCase);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        System.out.println(value);

        // asMap 返回 ConcurrentMap 对象，添加缓存时不会触发 build 方法中定义的 CacheLoader
        ConcurrentMap<String, String> concurrentMap = cache.asMap();
        concurrentMap.put("key", "value");
        System.out.println(cache.getIfPresent("key"));
    }

    public LoadingCache<String, String> initial() {
        return CacheBuilder.newBuilder()
//                .initialCapacity(16)
                .maximumSize(1000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
//                .concurrencyLevel(32)
                .build(
                        new CacheLoader<>() {
                            @Override
                            public String load(String key) {
                                return String.valueOf(key.hashCode());
                            }
                        });
    }
}
