package cache.concurrenthashmap;

import java.util.concurrent.ConcurrentHashMap;

public class TestPut {


    public static void main(String[] args) {
        ConcurrentHashMap<String, String> concurrentHashMap = new ConcurrentHashMap<>(15, 0.75F, 1);

        for (int i = 0; i < 23; i++) {
            concurrentHashMap.put(i + "", i + "");
        }
        // test addCount() and test transfer()
        concurrentHashMap.put("24", "24");
    }

}
