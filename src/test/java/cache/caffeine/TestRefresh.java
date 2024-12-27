package cache.caffeine;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;

public class TestRefresh {

    @Test
    public void testRefresh() throws InterruptedException {
        LoadingCache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(10L)
                // 虽然在这里配置了写后失效策略和刷新策略，但是刷新策略并不会主动执行，而是在元素被写之后才触发
                // 这样就不会产生配置了刷新策略导致过期策略中时间被重置的问题
                // 刷新与驱逐不同的是：驱逐执行期间返回
                .expireAfterWrite(Duration.ofSeconds(3))
                .refreshAfterWrite(Duration.ofSeconds(1))
                .build(new CacheLoader<String, String>() {
                    @Override
                    public @Nullable String load(String key) throws Exception {
                        return key.toUpperCase();
                    }

                    // refresh 专属方法，在元素被刷新时调用
                    @Override
                    public @Nullable String reload(String key, String oldValue) throws Exception {
                        return key + "-" + oldValue;
                    }
                });
        cache.put("key", "value");

        // 验证元素是否被刷新
        Thread.sleep(1100);
        System.out.println(cache.get("key"));

        // 验证元素触发过期策略
        Thread.sleep(2100);
        System.out.println(cache.get("key"));
        Thread.sleep(100);
        System.out.println(cache.get("key"));
    }

}
