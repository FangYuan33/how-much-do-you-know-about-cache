package cache.concurrenthashmap;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPerformance {

    @Test
    public void whenGetPut500KTimes()
            throws Exception {
        Map<String, Object> hashtable = new Hashtable<>();
        Map<String, Object> synchronizedHashMap =
                Collections.synchronizedMap(new HashMap<>());
        Map<String, Object> concurrentHashMap = new ConcurrentHashMap<>();

        long hashtableAvgRuntime = timeElapseForGetPut(hashtable);
        long syncHashMapAvgRuntime =
                timeElapseForGetPut(synchronizedHashMap);
        long concurrentHashMapAvgRuntime =
                timeElapseForGetPut(concurrentHashMap);

        assertTrue(hashtableAvgRuntime > concurrentHashMapAvgRuntime);
        assertTrue(syncHashMapAvgRuntime > concurrentHashMapAvgRuntime);
        System.out.println("Hashtable AvgRuntime: " + hashtableAvgRuntime);
        System.out.println("SynchronizedHashMap AvgRuntime: " + syncHashMapAvgRuntime);
        System.out.println("ConcurrentHashMap AvgRuntime: " + concurrentHashMapAvgRuntime);
    }

    private long timeElapseForGetPut(Map<String, Object> map) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(4);

        long startTime = System.nanoTime();
        for (int i = 0; i < 4; i++) {
            executorService.execute(() -> {
                for (int j = 0; j < 500_000; j++) {
                    int value = ThreadLocalRandom.current().nextInt(10000);
                    String key = String.valueOf(value);
                    map.put(key, value);
                    map.get(key);
                }
            });
        }
        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.MINUTES);
        return (System.nanoTime() - startTime) / 500_000;
    }

}
