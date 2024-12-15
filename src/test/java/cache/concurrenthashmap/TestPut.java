package cache.concurrenthashmap;

import java.util.concurrent.ConcurrentHashMap;

public class TestPut {


    public static void main(String[] args) {
        ConcurrentHashMap<String, String> concurrentHashMap = new ConcurrentHashMap<>(15, 0.75F, 1);

        concurrentHashMap.put("1", "1");
    }

}
