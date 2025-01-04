package cache.cacheline;

public final class T {

    public static class X extends Padding {
        // 8字节
        private volatile long x = 0L;
    }

    // 每个缓存行能存下 8*8 字节，X中字段为 8 字节，此处再添加 7 个实现 X[] 数组中两个元素的缓存对齐，分布在两个不同的缓存行中
    // 那么不同线程在访问这两个元素时，便不会发生内存伪共享问题，提高性能
    private static class Padding {
        // 7*8字节
        public long p1, p2, p3, p4, p5, p6, p7;
    }

    private static final X[] arr = new X[2];

    static {
        arr[0] = new X();
        arr[1] = new X();
    }

    public static void main(String[] args) throws InterruptedException {
        Thread thread1 = new Thread(() -> {
            for (long i = 0; i < 1000_0000L; i++) {
                // volatile的缓存一致性协议MESI或者锁总线，会消耗时间
                arr[0].x = i;
            }
        });

        Thread thread2 = new Thread(() -> {
            for (long i = 0; i < 1000_0000L; i++) {
                arr[1].x = i;
            }
        });
        long startTime = System.nanoTime();
        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        System.out.println("总计消耗时间：" + (System.nanoTime() - startTime) / 100_000);
    }
}

