package cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

// policy 提供了访问和操作过期策略或驱逐策略的途径
public class TestPolicy {

    @Test
    public void testPolicy() {
        // 创建一个缓存，设置最大大小和过期策略
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();

        // 添加一些缓存条目
        cache.put("key1", "value1");
        cache.put("key2", "value2");

        // 获取驱逐策略
        Optional<Policy.Eviction<String, String>> evictionPolicy = cache.policy().eviction();
        evictionPolicy.ifPresent(policy -> {
            System.out.println("Maximum size: " + policy.getMaximum());
            System.out.println("Current size: " + policy.weightedSize().orElse(0L));
//            policy.setMaximum();
        });

        // 获取过期策略
        Optional<Policy.FixedExpiration<String, String>> expirationPolicy = cache.policy().expireAfterWrite();
        expirationPolicy.ifPresent(policy -> {
            System.out.println("Expiration time: " + policy.getExpiresAfter(TimeUnit.MINUTES) + " minutes");
//            policy.setExpiresAfter();
        });

        // 检查某个条目的过期时间
        Optional<Policy.VarExpiration<String, String>> varExpirationPolicy = cache.policy().expireVariably();
        varExpirationPolicy.ifPresent(policy -> {
            long expirationTime = policy.getExpiresAfter("key1", TimeUnit.MINUTES).orElse(-1L);
            System.out.println("Expiration time for key1: " + expirationTime + " minutes");
//            policy.setExpiresAfter();
        });
    }

}
