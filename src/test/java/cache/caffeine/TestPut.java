package cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

public class TestPut {

    public static void main(String[] args) {
        Cache<String, String> cache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();

        String key = "key";

        // 查找一个缓存元素， 没有查找到的时候返回null
        String object = cache.getIfPresent(key);
        System.out.println(object);

        // 查找缓存，如果缓存不存在则生成缓存元素,  如果无法生成则返回null
        String defaultElement = cache.get(key, k -> "default");
        System.out.println(defaultElement);

        // 添加或者更新一个缓存元素
        cache.put(key, "value1");
        System.out.println(cache.getIfPresent(key));
        // 移除一个缓存元素
        cache.invalidate(key);
        System.out.println(cache.getIfPresent(key));
    }

}
