在 `com.github.benmanes.caffeine.cache` 包路径下能发现很多类似 `SSMSW` 只有简称的命名

为了代码复用使用了多级继承，以某个为例画类图

```java
interface LocalCacheFactory {

    static String getClassName(Caffeine<?, ?> builder) {
        var className = new StringBuilder();
        // key 是强引用或弱引用
        if (builder.isStrongKeys()) {
            className.append('S');
        } else {
            className.append('W');
        }
        // value 是强引用或弱引用
        if (builder.isStrongValues()) {
            className.append('S');
        } else {
            className.append('I');
        }
        // 配置了移除监听器
        if (builder.removalListener != null) {
            className.append('L');
        }
        // 配置了统计功能
        if (builder.isRecordingStats()) {
            className.append('S');
        }
        // 不同的驱逐策略
        if (builder.evicts()) {
            // 基于最大大小
            className.append('M');
            // 基于权重或非权重
            if (builder.isWeighted()) {
                className.append('W');
            } else {
                className.append('S');
            }
        }
        // 配置了访问过期或可变过期策略
        if (builder.expiresAfterAccess() || builder.expiresVariable()) {
            className.append('A');
        }
        // 配置了写入过期策略
        if (builder.expiresAfterWrite()) {
            className.append('W');
        }
        // 配置了刷新策略
        if (builder.refreshAfterWrite()) {
            className.append('R');
        }
        return className.toString();
    }
}
```

```java
interface NodeFactory<K, V> {
    // ...

    static String getClassName(Caffeine<?, ?> builder, boolean isAsync) {
        var className = new StringBuilder();
        // key 强引用或弱引用
        if (builder.isStrongKeys()) {
            className.append('P');
        } else {
            className.append('F');
        }
        // value 强引用或弱引用或软引用
        if (builder.isStrongValues()) {
            className.append('S');
        } else if (builder.isWeakValues()) {
            className.append('W');
        } else {
            className.append('D');
        }
        // 过期策略
        if (builder.expiresVariable()) {
            if (builder.refreshAfterWrite()) {
                // 访问后过期
                className.append('A');
                if (builder.evicts()) {
                    // 写入后过期
                    className.append('W');
                }
            } else {
                className.append('W');
            }
        } else {
            // 访问后过期
            if (builder.expiresAfterAccess()) {
                className.append('A');
            }
            // 写入后过期
            if (builder.expiresAfterWrite()) {
                className.append('W');
            }
        }
        // 写入后刷新
        if (builder.refreshAfterWrite()) {
            className.append('R');
        }
        // 驱逐策略
        if (builder.evicts()) {
            // 默认最大大小限制
            className.append('M');
            // 加权
            if (isAsync || (builder.isWeighted() && (builder.weigher != Weigher.singletonWeigher()))) {
                className.append('W');
            } else {
                // 非加权
                className.append('S');
            }
        }
        return className.toString();
    }

}
```

### put

插入一个不存在的 key

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    final MpscGrowableArrayQueue<Runnable> writeBuffer;

    final ConcurrentHashMap<Object, Node<K, V>> data;
    final PerformCleanupTask drainBuffersTask;

    final ReentrantLock evictionLock;

    final NodeFactory<K, V> nodeFactory;

    @Nullable
    V put(K key, V value, Expiry<K, V> expiry, boolean onlyIfAbsent) {
        // 不允许添加 null
        requireNonNull(key);
        requireNonNull(value);

        Node<K, V> node = null;
        // 获取当前时间戳
        long now = expirationTicker().read();
        // 计算缓存权重，如果没有指定 weigher 的话，默认权重为 1
        int newWeight = weigher.weigh(key, value);
        // 创建用于查找的键对象
        Object lookupKey = nodeFactory.newLookupKey(key);
        // 无限循环
        for (int attempts = 1; ; attempts++) {
            // 尝试获取节点；prior 译为先前的；较早的
            Node<K, V> prior = data.get(lookupKey);
            // 处理不存在的节点
            if (prior == null) {
                // 如果 node 在循环执行中还未被初始化，则初始化它
                if (node == null) {
                    node = nodeFactory.newNode(key, keyReferenceQueue(),
                            value, valueReferenceQueue(), newWeight, now);
                    // 设置节点的过期时间
                    setVariableTime(node, expireAfterCreate(key, value, expiry, now));
                }
                // 尝试添加新节点到缓存中，如果键已存在则返回现有节点
                prior = data.putIfAbsent(node.getKeyReference(), node);
                // 返回 null 表示插入成功
                if (prior == null) {
                    // 该方法用于写操作后执行任务
                    afterWrite(new AddTask(node, newWeight));
                    return null;
                }
                // ...
            }
            // ...
        }
        // ...
    }

    // 添加 Task 到 writeBuffer 中并在合适的时机调度任务
    void afterWrite(Runnable task) {
        for (int i = 0; i < WRITE_BUFFER_RETRIES; i++) {
            if (writeBuffer.offer(task)) {
                // 写后调度
                scheduleAfterWrite();
                return;
            }
            // 任务添加失败直接调度任务执行
            scheduleDrainBuffers();
            // 自旋等待，让出 CPU 控制权
            Thread.onSpinWait();
        }
        // ...
    }

    void scheduleAfterWrite() {
        // 获取当前 drainStatus，drain 译为排空，耗尽
        int drainStatus = drainStatusOpaque();
        for (; ; ) {
            // 这里的状态机变更需要关注下
            switch (drainStatus) {
                // IDLE 表示当前无任务可做
                case IDLE:
                    // CAS 更新状态为 REQUIRED
                    casDrainStatus(IDLE, REQUIRED);
                    // 调度任务执行
                    scheduleDrainBuffers();
                    return;
                // REQUIRED 表示当前有任务需要执行
                case REQUIRED:
                    // 调度任务执行
                    scheduleDrainBuffers();
                    return;
                // PROCESSING_TO_IDLE 表示当前任务处理完成后会变成 IDLE 状态
                case PROCESSING_TO_IDLE:
                    // 又来了新的任务，则 CAS 操作将它更新为 PROCESSING_TO_REQUIRED 状态
                    if (casDrainStatus(PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED)) {
                        return;
                    }
                    drainStatus = drainStatusAcquire();
                    continue;
                    // PROCESSING_TO_REQUIRED 表示正在处理任务，处理完任务后还有任务需要处理
                case PROCESSING_TO_REQUIRED:
                    return;
                default:
                    throw new IllegalStateException("Invalid drain status: " + drainStatus);
            }
        }
    }

    // 调度处理 drainBuffersTask
    void scheduleDrainBuffers() {
        // 如果状态表示正在有任务处理则返回
        if (drainStatusOpaque() >= PROCESSING_TO_IDLE) {
            return;
        }
        // 注意这里获取了同步锁 evictionLock
        if (evictionLock.tryLock()) {
            try {
                // 获取锁后再次校验当前处理状态
                int drainStatus = drainStatusOpaque();
                if (drainStatus >= PROCESSING_TO_IDLE) {
                    return;
                }
                // 更新状态为 PROCESSING_TO_IDLE
                setDrainStatusRelease(PROCESSING_TO_IDLE);
                // 同步机制保证任何时刻只能有一个线程能够提交任务
                executor.execute(drainBuffersTask);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Exception thrown when submitting maintenance task", t);
                maintenance(/* ignored */ null);
            } finally {
                evictionLock.unlock();
            }
        }
    }

}
```

任务处理中状态时，其他线程是不能提交任务的

接线来我们看一下要执行的任务 `PerformCleanupTask`

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    final ReentrantLock evictionLock;

    // 可重用的维护任务，避免使用 ForkJoinPool 来包装
    static final class PerformCleanupTask extends ForkJoinTask<Void> implements Runnable {
        private static final long serialVersionUID = 1L;

        final WeakReference<BoundedLocalCache<?, ?>> reference;

        PerformCleanupTask(BoundedLocalCache<?, ?> cache) {
            reference = new WeakReference<BoundedLocalCache<?, ?>>(cache);
        }

        @Override
        public boolean exec() {
            try {
                run();
            } catch (Throwable t) {
                logger.log(Level.ERROR, "Exception thrown when performing the maintenance task", t);
            }

            // Indicates that the task has not completed to allow subsequent submissions to execute
            return false;
        }

        @Override
        public void run() {
            BoundedLocalCache<?, ?> cache = reference.get();
            if (cache != null) {
                cache.performCleanUp(/* ignored */ null);
            }
        }
        // ...
    }

    // 执行维护任务时，也获取了同步锁，表示维护任务只能由一个线程来完成
    void performCleanUp(@Nullable Runnable task) {
        evictionLock.lock();
        try {
            // 执行维护任务
            maintenance(task);
        } finally {
            evictionLock.unlock();
        }
        rescheduleCleanUpIfIncomplete();
    }

    @GuardedBy("evictionLock")
    void maintenance(@Nullable Runnable task) {
        // 更新状态为执行中
        setDrainStatusRelease(PROCESSING_TO_IDLE);

        try {
            // 处理读缓冲区中的任务
            drainReadBuffer();

            // 处理写缓冲区中的任务
            drainWriteBuffer();
            if (task != null) {
                task.run();
            }

            // 处理 key 和 value 的引用
            drainKeyReferences();
            drainValueReferences();

            // 过期和驱逐策略
            expireEntries();
            evictEntries();

            // 其他操作，后续我们再重点讲
            climb();
        } finally {
            // 状态不是 PROCESSING_TO_IDLE 或者无法 CAS 更新为 IDLE 状态的话，需要更新状态为 REQUIRED，该状态会再次执行维护任务
            if ((drainStatusOpaque() != PROCESSING_TO_IDLE) || !casDrainStatus(PROCESSING_TO_IDLE, IDLE)) {
                setDrainStatusOpaque(REQUIRED);
            }
        }
    }
}
```

本次我们先关注 `drainWriteBuffer` 处理写缓冲区中的任务，`put` 元素对应的任务为 `AddTask`

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;
    
    final class AddTask implements Runnable {
        final Node<K, V> node;
        final int weight;

        AddTask(Node<K, V> node, int weight) {
            this.weight = weight;
            this.node = node;
        }

        @Override
        @GuardedBy("evictionLock")
        @SuppressWarnings("FutureReturnValueIgnored")
        public void run() {
            // 是否需要被驱逐
            if (evicts()) {
                // 更新总权重和窗口权重
                setWeightedSize(weightedSize() + weight);
                setWindowWeightedSize(windowWeightedSize() + weight);
                // 更新 policy 权重
                node.setPolicyWeight(node.getPolicyWeight() + weight);

                // 检测当前总权重是否超过一半的最大容量
                long maximum = maximum();
                if (weightedSize() >= (maximum >>> 1)) {
                    // 如果超过最大容量
                    if (weightedSize() > MAXIMUM_CAPACITY) {
                        // 执行驱逐操作
                        evictEntries();
                    } else {
                        // 延迟加载 frequencySketch 数据结构，用于统计元素访问频率
                        long capacity = isWeighted() ? data.mappingCount() : maximum;
                        frequencySketch().ensureCapacity(capacity);
                    }
                }

                // 更新频率统计信息
                K key = node.getKey();
                if (key != null) {
                    frequencySketch().increment(key);
                }

                // 增加未命中样本数
                setMissesInSample(missesInSample() + 1);
            }

            // 同步检测节点是否还有效
            boolean isAlive;
            synchronized (node) {
                isAlive = node.isAlive();
            }
            if (isAlive) {
                // 写后过期策略
                if (expiresAfterWrite()) {
                    writeOrderDeque().offerLast(node);
                }
                // 过期策略
                if (expiresVariable()) {
                    timerWheel().schedule(node);
                }
                // 驱逐策略
                if (evicts()) {
                    // 如果权重比配置的权重大
                    if (weight > maximum()) {
                        // 驱逐策略
                        evictEntry(node, RemovalCause.SIZE, expirationTicker().read());
                    }
                    // 如果权重超过窗口最大权重，放在头节点
                    else if (weight > windowMaximum()) {
                        accessOrderWindowDeque().offerFirst(node);
                    }
                    // 否则放在尾节点
                    else {
                        accessOrderWindowDeque().offerLast(node);
                    }
                }
                // 访问后过期策略
                else if (expiresAfterAccess()) {
                    accessOrderWindowDeque().offerLast(node);
                }
            }

            // 处理异步计算
            if (isComputingAsync(node)) {
                synchronized (node) {
                    if (!Async.isReady((CompletableFuture<?>) node.getValue())) {
                        long expirationTime = expirationTicker().read() + ASYNC_EXPIRY;
                        setVariableTime(node, expirationTime);
                        setAccessTime(node, expirationTime);
                        setWriteTime(node, expirationTime);
                    }
                }
            }
        }
    }
}
```

在这个过程中我们简单了解了 `put` 添加缓存中不存在的元素的处理流程，需要知道元素是被直接添加到 `ConcurrentHashMap data` 中的，至于其他驱逐、过期等维护操作是由任务异步驱动完成的，而且只能由单线程去处理，至于其中详细的逻辑在后文中介绍。

### getIfPresent

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    final ConcurrentHashMap<Object, Node<K, V>> data;

    final Buffer<Node<K, V>> readBuffer;
    
    @Override
    public @Nullable V getIfPresent(Object key, boolean recordStats) {
        // 直接由 ConcurrentHashMap 获取元素
        Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
        if (node == null) {
            // 更新统计未命中
            if (recordStats) {
                statsCounter().recordMisses(1);
            }
            // 当前 drainStatus 为 REQUIRED 表示有任务需要处理则调度处理
            if (drainStatusOpaque() == REQUIRED) {
                scheduleDrainBuffers();
            }
            return null;
        }

        V value = node.getValue();
        long now = expirationTicker().read();
        // 判断是否过期或者需要被回收且value对应的值为null
        if (hasExpired(node, now) || (collectValues() && (value == null))) {
            // 更新统计未命中
            if (recordStats) {
                statsCounter().recordMisses(1);
            }
            scheduleDrainBuffers();
            return null;
        }

        // 检查节点没有在进行异步计算
        if (!isComputingAsync(node)) {
            @SuppressWarnings("unchecked")
            K castedKey = (K) key;
            // 更新访问时间
            setAccessTime(node, now);
            // 更新读后过期时间
            tryExpireAfterRead(node, castedKey, value, expiry(), now);
        }
        // 处理读取后操作
        V refreshed = afterRead(node, now, recordStats);
        return (refreshed == null) ? value : refreshed;
    }

    @Nullable V afterRead(Node<K, V> node, long now, boolean recordHit) {
        // 更新统计命中
        if (recordHit) {
            statsCounter().recordHits(1);
        }

        // 注意这里如果 readBuffer 已经被初始化不需要被跳过，它会执行 readBuffer.offer(node) 逻辑，添加待处理元素
        // 没有满的话为 true
        boolean delayable = skipReadBuffer() || (readBuffer.offer(node) != Buffer.FULL);
        // 判断是否需要处理维护任务
        if (shouldDrainBuffers(delayable)) {
            scheduleDrainBuffers();
        }
        // 处理必要的刷新操作
        return refreshIfNeeded(node, now);
    }

    // 状态流转，没有满 delayable 为 true 表示延迟执行维护任务
    boolean shouldDrainBuffers(boolean delayable) {
        switch (drainStatusOpaque()) {
            case IDLE:
                return !delayable;
            // 当前有任务需要处理则调度维护任务执行，否则均延迟执行    
            case REQUIRED:
                return true;
            case PROCESSING_TO_IDLE:
            case PROCESSING_TO_REQUIRED:
                return false;
            default:
                throw new IllegalStateException("Invalid drain status: " + drainStatus);
        }
    }
}
```
