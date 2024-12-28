package cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import org.checkerframework.checker.index.qual.NonNegative;
import org.junit.jupiter.api.Test;

public class TestStatistics {

    @Test
    public void statistics() {
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(10)
                // 无参默认使用的是 ConcurrentStatsCounter
                .recordStats(CustomerStatsCounter::new)
                .build();

        // 操作缓存
        performCacheOperations(cache);

        // 打印统计信息
        CacheStats stats = cache.stats();
        System.out.println("命中率：" + stats.hitRate());
        System.out.println("被驱逐缓存数量：" + stats.evictionCount());
        System.out.println("新值加载时间耗时：" + stats.averageLoadPenalty());
    }

    private void performCacheOperations(Cache<String, String> cache) {
        // 插入数据
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");

        // 命中
        System.out.println("key1: " + cache.getIfPresent("key1")); // hit
        System.out.println("key2: " + cache.getIfPresent("key2")); // hit

        // 未命中
        System.out.println("key4: " + cache.getIfPresent("key4")); // miss

        // 更新数据
        cache.put("key1", "newValue1");

        // 再次命中
        System.out.println("key1: " + cache.getIfPresent("key1")); // hit

        // 填充缓存，触发驱逐
        for (int i = 4; i <= 12; i++) {
            cache.put("key" + i, "value" + i);
        }

        // 验证驱逐
        System.out.println("key1: " + cache.getIfPresent("key1")); // should be evicted if maximumSize is exceeded
    }

    static class CustomerStatsCounter implements StatsCounter {

        private final StatsCounter delegate = new ConcurrentStatsCounter();

        @Override
        public void recordHits(int count) {
            delegate.recordHits(count);
            // 自定义统计逻辑
        }


        @Override
        public void recordMisses(int count) {
            delegate.recordMisses(count);
        }


        @Override
        public void recordLoadSuccess(long loadTime) {
            delegate.recordLoadSuccess(loadTime);
        }


        @Override
        public void recordLoadFailure(long loadTime) {
            delegate.recordLoadFailure(loadTime);
        }

        @Override
        public void recordEviction(@NonNegative int weight, RemovalCause cause) {
            delegate.recordEviction(weight, cause);
        }

        @Override
        public CacheStats snapshot() {
            return delegate.snapshot();
        }
    }

}
