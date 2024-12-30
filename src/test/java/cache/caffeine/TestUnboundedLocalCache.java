package cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.junit.jupiter.api.Test;

public class TestUnboundedLocalCache {

    @Test
    public void test() {
        // 创建一个没有大小限制的缓存，配置统计信息和监听器
        Cache<String, String> cache = Caffeine.newBuilder()
                .recordStats()
                .removalListener(new RemovalListener<String, String>() {
                    @Override
                    public void onRemoval(String key, String value, RemovalCause cause) {
                        System.out.println("Removed key: " + key + ", cause: " + cause);
                    }
                })
                // isBounded 方法中有判断逻辑
                .build();

        // 添加一些条目
        cache.put("key1", "value1");
        cache.put("key2", "value2");

        // 获取条目
        System.out.println("key1: " + cache.getIfPresent("key1"));
        System.out.println("key2: " + cache.getIfPresent("key2"));

        // 打印统计信息
        System.out.println("Cache stats: " + cache.stats());
    }

}
