package cache.caffeine;

import com.github.benmanes.caffeine.cache.*;
import org.checkerframework.checker.index.qual.NonNegative;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// 验证驱逐策略
// 在写操作，和偶尔的读操作中将会进行周期性的过期事件的执行
public class TestEviction {

    @Test
    public void size() {
        // 创建一个最大容量为 100 的缓存
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(100)
                .build();

        // 插入数据
        for (int i = 0; i < 200; i++) {
            cache.put("key" + i, "value" + i);
        }

        // 获取数据
        String value = cache.getIfPresent("key1");
        System.out.println("Value for key1: " + value);

        String value2 = cache.getIfPresent("key1");
        System.out.println("Value for key1: " + value2);
    }

    @Test
    public void weight() {
        // 创建一个最大权重为 1000 的缓存
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumWeight(1)
                // 指定计算每对 entry 的权重
                .weigher((String key, String value) -> value.length())
                .build();

        // 插入数据
        cache.put("key1", "value1");
        cache.put("key2", "value2");

        // 获取数据
        String value = cache.getIfPresent("key1");
        System.out.println("Value for key1: " + value);

        String value2 = cache.getIfPresent("key1");
        System.out.println("Value for key1: " + value2);
    }

    @Test
    public void time() {
        // 创建一个写入后 5 秒自动过期的缓存
        Cache<String, String> cache = Caffeine.newBuilder()
                // 一个元素将会在其创建或者最近一次被 更新 之后的一段时间后被认定为过期项
                .expireAfterWrite(5, TimeUnit.SECONDS)
                // 一个元素在上一次 读写 操作后一段时间之后，在指定的时间后没有被再次访问将会被认定为过期项
//                .expireAfterAccess(5, TimeUnit.SECONDS)
                //
                .expireAfter(new Expiry<Object, Object>() {
                    @Override
                    public long expireAfterCreate(Object key, Object value, long currentTime) {
                        // 设置条目在创建后 10 秒过期
                        return TimeUnit.SECONDS.toNanos(10);
                    }

                    @Override
                    public long expireAfterUpdate(Object key, Object value, long currentTime, @NonNegative long currentDuration) {
                        // 默认过期策略
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(Object key, Object value, long currentTime, @NonNegative long currentDuration) {
                        // 默认过期策略
                        return currentDuration;
                    }
                })
                // 该方法允许你配置缓存的过期条目在没有任何缓存活动时也能被及时清理，扫描周期在 caffeine 内部实现
                .scheduler(Scheduler.forScheduledExecutorService(Executors.newScheduledThreadPool(1)))
                .build();

        // 插入数据
        cache.put("key1", "value1");

        // 获取数据
        String value = cache.getIfPresent("key1");
        System.out.println("Value for key1: " + value);

        // 等待 6 秒
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 获取数据
        value = cache.getIfPresent("key1");
        System.out.println("Value for key1 after 6 seconds: " + value);
    }

    // 模拟时钟
    @Test
    public void tickerTime() {
        // 创建自定义的 Ticker 对象，用于模拟时间流逝
        Ticker ticker = new Ticker() {
            private long startTime = System.nanoTime();

            @Override
            public long read() {
                // 模拟时间流逝，每次调用增加 1 秒
                startTime += TimeUnit.SECONDS.toNanos(1);
                return startTime;
            }
        };

        Cache<String, String> cache = Caffeine.newBuilder()
                // 创建一个写入后 5 秒自动过期的缓存
                .expireAfterAccess(5, TimeUnit.SECONDS)
                .ticker(ticker)
                .build();

        cache.put("key1", "value1");

        System.out.println(cache.getIfPresent("key1"));
        // 时间流逝 6s
        for (int i = 0; i < 6; i++) {
            ticker.read();
        }
        System.out.println(cache.getIfPresent("key1"));
    }

    @Test
    public void reference() {
        // 创建一个根据引用失效的 caffeine 缓存
        Cache<String, String> cache = Caffeine.newBuilder()
                .weakKeys()
                .weakValues()
                .build();

        // 软引用缓存，文档中强调使用该缓存可能会影响性能，建议使用基于缓存容量的驱逐策略
        Cache<Object, Object> softwareCache = Caffeine.newBuilder().softValues().build();

    }

}
