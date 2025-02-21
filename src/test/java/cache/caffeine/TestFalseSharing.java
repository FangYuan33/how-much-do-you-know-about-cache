package cache.caffeine;

import org.junit.jupiter.api.Test;

public class TestFalseSharing {

    static class Pointer {
        volatile long x;
        long p1, p2, p3, p4, p5, p6, p7;
        volatile long y;

        @Override
        public String toString() {
            return "x=" + x + ", y=" + y;
        }
    }

    @Test
    public void testFalseSharing() throws InterruptedException {
        Pointer pointer = new Pointer();

        // 启动两个线程，分别对 x 和 y 进行自增 1亿 次的操作
        long start = System.currentTimeMillis();
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100_000_000; i++) {
                pointer.x++;
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100_000_000; i++) {
                pointer.y++;
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println(System.currentTimeMillis() - start);
        System.out.println(pointer);
    }

}
