package cache.concurrenthashmap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class TestThreadSafely {

    @Test
    public void testHashMap() throws Exception {
        Map<String, Integer> map = new HashMap<>();
        parallelSum100(map);
    }

    @Test
    public void testConcurrentHashMap() throws Exception {
        Map<String, Integer> map = new ConcurrentHashMap<>();
        parallelSum100(map);
    }

    private void parallelSum100(Map<String, Integer> map) throws InterruptedException {
        List<Integer> sumList = doParallelSum100(map, 100);

        long count = sumList.stream().distinct().count();
        System.out.println("执行结果不同的数量：" + count);

        long wrongResultCount = sumList.stream().filter(num -> num != 100).count();
        System.out.println("错误数量：" + wrongResultCount);
    }

    // 多线程计算求和 100，在 sumList 中统计 100 次执行结果
    private List<Integer> doParallelSum100(Map<String, Integer> map, int executionTimes) throws InterruptedException {
        List<Integer> sumList = new ArrayList<>(1000);
        for (int i = 0; i < executionTimes; i++) {
            map.put("test", 0);

            ExecutorService executorService = Executors.newFixedThreadPool(4);
            for (int j = 0; j < 10; j++) {
                executorService.execute(() -> {
                    for (int k = 0; k < 10; k++)
                        map.computeIfPresent(
                                "test",
                                (key, value) -> value + 1
                        );
                });
            }
            executorService.shutdown();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
            sumList.add(map.get("test"));
        }
        return sumList;
    }

}
