package cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentMap;

public class TestCompute {

    @Test
    public void testCompute() {
        Cache<String, Integer> cache = Caffeine.newBuilder()
                .maximumSize(10)
                .build();

        // caffeine asMap 方法允许获取 ConcurrentMap 对象
        ConcurrentMap<String, Integer> concurrentMap = cache.asMap();
        // 使用 compute 方法可以条件更新 value 的值，并将计算出的新值更新到键值对中，如下为计数器的例子

        String key = "key";
        concurrentMap.compute(key, (k, v) -> v == null ? 1 : v + 1);
        concurrentMap.compute(key, (k, v) -> v == null ? 1 : v + 1);
        System.out.println(concurrentMap.get(key));
    }

}
