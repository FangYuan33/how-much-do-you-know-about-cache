package cache.caffeine;

import com.github.benmanes.caffeine.cache.*;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;

// 添加
public class TestPopulation {

    // 手动添加
    @Test
    public void manual() {
        Cache<String, String> cache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();

        String key = "key";

        // 查找一个缓存元素，没有查找到的时候返回 null
        String value = cache.getIfPresent(key);
        System.out.println(value);

        // 查找缓存，如果缓存不存在则生成缓存元素，如果无法生成则返回 null
        String defaultElement = cache.get(key, k -> "default");
        System.out.println(defaultElement);

        // 添加或者更新一个缓存元素
        cache.put(key, "value1");
        System.out.println(cache.getIfPresent(key));
        // 移除一个缓存元素
        cache.invalidate(key);
        System.out.println(cache.getIfPresent(key));
    }

    // 自动加载
    @Test
    public void loading() {
        // LoadingCache 是一个 Cache 附加上 CacheLoader 能力之后的缓存实现
        LoadingCache<String, String> cache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                // 查找缓存时，如果缓存不存在则自动生成
                .build(key -> "defaultValue");

        String value = cache.get("key");
        System.out.println(value);

        // 通过 getAll 可以达到批量查找缓存的目的，默认情况下，getAll 会对每个不存在的 key 都调用一次 CacheLoader.load 来生成缓存元素
        Map<String, String> all = cache.getAll(Arrays.asList("key1", "key2", "key3"));
        System.out.println(all);
    }

    // 手动异步加载
    @Test
    public void asyncManual() {
        // AsyncCache 是 cache 的变体，AsyncCache 提供了在 Executor 上生成缓存元素并返回 CompletableFuture 的功能
        // 这使得能够在当前流行的响应式编程模型中利用缓存的能力
        AsyncCache<String, String> cache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                // 默认的线程池实现是 ForkJoinPool.commonPool()，也可以通过 Caffeine.executor(Executor) 方法来自定义线程池选择
                .executor(new ThreadPoolExecutor(8, 8, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()))
                .buildAsync();

        CompletableFuture<String> value = cache.getIfPresent("key");

        CompletableFuture<String> value2 = cache.get("key", key -> "defaultValue");
        System.out.println(value2.join());

        CompletableFuture<String> value3 = CompletableFuture.supplyAsync(() -> "defaultValue");
        cache.put("key", value3);

        // 删除元素，synchronous()方法提供了同步调用的能力
        cache.synchronous().invalidate("key");
    }

    // 自动异步加载
    @Test
    public void asyncLoading() throws ExecutionException, InterruptedException {
        AsyncLoadingCache<String, String> cache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .buildAsync(key -> "defaultValue");

        // 如果不存在会异步自动生成
        CompletableFuture<String> value = cache.get("key");
        System.out.println(value.get());
    }
}
