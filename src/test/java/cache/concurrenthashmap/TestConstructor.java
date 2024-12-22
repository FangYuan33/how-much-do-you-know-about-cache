package cache.concurrenthashmap;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

public class TestConstructor {

    @Test
    public void test() {
        ConcurrentHashMap<String, String> concurrentHashMap = new ConcurrentHashMap<>();

        concurrentHashMap.put("key1", "value1");
    }

}
