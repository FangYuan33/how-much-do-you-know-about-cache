

```java
class LocalCache<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {

    final int concurrencyLevel;

    final Strength keyStrength;
    final Strength valueStrength;
    final Equivalence<Object> keyEquivalence;
    final Equivalence<Object> valueEquivalence;

    final long maxWeight;
    final Weigher<K, V> weigher;

    final long expireAfterAccessNanos;
    final long expireAfterWriteNanos;
    final long refreshNanos;

    final RemovalListener<K, V> removalListener;
    final Queue<RemovalNotification<K, V>> removalNotificationQueue;

    final Ticker ticker;
    final EntryFactory entryFactory;
    final StatsCounter globalStatsCounter;
    @CheckForNull final CacheLoader<? super K, V> defaultLoader;

    final int segmentMask;
    final int segmentShift;
    final Segment<K, V>[] segments;
    
    LocalCache(
            CacheBuilder<? super K, ? super V> builder, @CheckForNull CacheLoader<? super K, V> loader) {
        
        // 并发级别，不指定默认为 4
        concurrencyLevel = min(builder.getConcurrencyLevel(), MAX_SEGMENTS);

        // key 和 value 的引用强度，默认为强引用
        keyStrength = builder.getKeyStrength();
        valueStrength = builder.getValueStrength();

        // 键值比较器，默认为强引用比较器
        keyEquivalence = builder.getKeyEquivalence();
        valueEquivalence = builder.getValueEquivalence();

        // maxWeight 最大权重，指定了为 1000
        maxWeight = builder.getMaximumWeight();
        // weigher 没有指定，默认为 1，表示每个元素的权重为 1
        weigher = builder.getWeigher();
        // 访问后和写后过期时间，默认为 0，表示不设置过期时间
        expireAfterAccessNanos = builder.getExpireAfterAccessNanos();
        expireAfterWriteNanos = builder.getExpireAfterWriteNanos();
        // 刷新时间，默认为 0，表示不刷新
        refreshNanos = builder.getRefreshNanos();

        // 元素驱逐监听器
        removalListener = builder.getRemovalListener();
        removalNotificationQueue =
                (removalListener == NullListener.INSTANCE)
                        ? LocalCache.discardingQueue()
                        : new ConcurrentLinkedQueue<>();

        // 定义 Ticker 可以模拟时间流动来验证逻辑
        ticker = builder.getTicker(recordsTime());
        entryFactory = EntryFactory.getFactory(keyStrength, usesAccessEntries(), usesWriteEntries());
        globalStatsCounter = builder.getStatsCounterSupplier().get();
        defaultLoader = loader;

        // 初始化大小，默认为 16
        int initialCapacity = min(builder.getInitialCapacity(), MAXIMUM_CAPACITY);
        if (evictsBySize() && !customWeigher()) {
            initialCapacity = (int) min(initialCapacity, maxWeight);
        }
        
        // 基于大小的驱逐策略是针对每个段而不是全局进行驱逐的，因此段数过多会导致随机的驱逐行为
        // 计算分段数量和分段位移（shift）的逻辑
        int segmentShift = 0;
        int segmentCount = 1;
        // 保证分段数量不低于并发级别 且 段数*20不超过最大权重，保证每个段的元素数量至少为 10
        while (segmentCount < concurrencyLevel
                && (!evictsBySize() || segmentCount * 20L <= maxWeight)) {
            // 分段位移+1
            ++segmentShift;
            // 分段数量为 2的n次幂
            segmentCount <<= 1;
        }
        this.segmentShift = 32 - segmentShift;
        // 分段掩码值为分段数量-1
        segmentMask = segmentCount - 1;

        // 创建分段数组
        this.segments = newSegmentArray(segmentCount);

        // 计算每个分段的大小
        int segmentCapacity = initialCapacity / segmentCount;
        if (segmentCapacity * segmentCount < initialCapacity) {
            ++segmentCapacity;
        }
        int segmentSize = 1;
        while (segmentSize < segmentCapacity) {
            segmentSize <<= 1;
        }
        // 如果指定了基于大小的驱逐策略，那么要保证所有分段的最大值之和（maxSegmentWeight）要超过指定的最大容量
        if (evictsBySize()) {
            long maxSegmentWeight = maxWeight / segmentCount + 1;
            long remainder = maxWeight % segmentCount;
            for (int i = 0; i < this.segments.length; ++i) {
                if (i == remainder) {
                    maxSegmentWeight--;
                }
                // 创建 Segment 对象，segmentSize为4，maxSegmentWeight为250
                this.segments[i] =
                        createSegment(segmentSize, maxSegmentWeight, builder.getStatsCounterSupplier().get());
            }
        }
        // 如果未指定基于大小的驱逐策略，创建的 Segment 对象的 maxSegmentWeight 为 UNSET_INT
        else {
            for (int i = 0; i < this.segments.length; ++i) {
                this.segments[i] =
                        createSegment(segmentSize, UNSET_INT, builder.getStatsCounterSupplier().get());
            }
        }
    }
}
```

我们接着看下创建 Segment 的方法 `LocalCache#createSegment`，它直接调用了 `Segment` 的构造方法：

```java
class LocalCache<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    // ...
    
    Segment<K, V> createSegment(int initialCapacity, long maxSegmentWeight, StatsCounter statsCounter) {
        return new Segment<>(this, initialCapacity, maxSegmentWeight, statsCounter);
    }
}
```

`Segment` 是 `LocalCache` 内的静态内部类，根据它的中文释义，它可能在缓存中起到了将数据分段管理的作用。它继承了 `ReentrantLock` 主要为了简化锁定的操作：

```java
static class Segment<K, V> extends ReentrantLock {
    // ...
}
```

在其中一段 JavaDoc 值得读一下：

Segments 维护键值对列表并且一直保持数据一致性。因此可以在不加锁的情况下进行读操作。键值对对象中的 `next` 字段被 `final` 修饰，所有的列表添加操作都只能在每个桶的前面进行，这也就使得检查变更比较容易，并且遍历速度也比较快。当节点需要更改时，会创建新节点来替换它们。这对于哈希表来说效果很好，因为桶列表往往很短（平均长度小于二）。

因此，读操作可以在不加锁的情况下进行，但依赖于被 `volatile` 关键字修饰的变量，因为这个关键字能确保“可见性”。对大多数操作来说，可以将记录元素数量的字段 `count` 来作为确保可见性的变量。它带来了很多便利，在很多读操作中都需要参考这个字段：

- 未加锁的读操作必须首先读取 `count` 字段，如果它是 0，则不应读任何元素
- 加锁的写操作在任何桶发生结构性更改后都需要修改 `count` 字段值，这些写操作不能再任何情况下导致并发读操作发生读取数据不一致的情况，这样的保证使得 Map 中的读操作更容易。比如，没有操作可以揭示 Map 中添加了新的元素但是 `count` 字段没有被更新的情况，因此相对于读取没有原子性要求。

作为指南，所有关键的被 `volatile` 修饰的字段的读取和写入都会用注释标记。

>     /*
     * Segments maintain a table of entry lists that are ALWAYS kept in a consistent state, so can
     * be read without locking. Next fields of nodes are immutable (final). All list additions are
     * performed at the front of each bin. This makes it easy to check changes, and also fast to
     * traverse. When nodes would otherwise be changed, new nodes are created to replace them. This
     * works well for hash tables since the bin lists tend to be short. (The average length is less
     * than two.)
     *
     * Read operations can thus proceed without locking, but rely on selected uses of volatiles to
     * ensure that completed write operations performed by other threads are noticed. For most
     * purposes, the "count" field, tracking the number of elements, serves as that volatile
     * variable ensuring visibility. This is convenient because this field needs to be read in many
     * read operations anyway:
     *
     * - All (unsynchronized) read operations must first read the "count" field, and should not look
     * at table entries if it is 0.
     *
     * - All (synchronized) write operations should write to the "count" field after structurally
     * changing any bin. The operations must not take any action that could even momentarily cause a
     * concurrent read operation to see inconsistent data. This is made easier by the nature of the
     * read operations in Map. For example, no operation can reveal that the table has grown but the
     * threshold has not yet been updated, so there are no atomicity requirements for this with
     * respect to reads.
     *
     * As a guide, all critical volatile reads and writes to the count field are marked in code
     * comments.
     */

通过它的 JavaDoc 我们可以暂时的了解到它通过写操作的数据一致性保证和被 `volatile` 修饰的字段来实现无锁的读操作，不过其中键值对中被 `final` 修饰的 `next` 字段究竟是怎么回事就需要在后文中去探究了。下面我们根据它的构造方法看一下该类中比较重要的字段信息：

```java
static class Segment<K, V> extends ReentrantLock {

    // 在某一段（Segment）中元素的数量
    volatile int count;

    // 总的权重值
    @GuardedBy("this")
    long totalWeight;

    // 修改次数
    int modCount;

    // 继上一次写操作后读操作的数量
    final AtomicInteger readCount = new AtomicInteger();
    
    @Weak final LocalCache<K, V> map;

    final long maxSegmentWeight;

    final StatsCounter statsCounter;

    int threshold;

    @CheckForNull volatile AtomicReferenceArray<ReferenceEntry<K, V>> table;

    final Queue<ReferenceEntry<K, V>> recencyQueue;

    @GuardedBy("this")
    final Queue<ReferenceEntry<K, V>> writeQueue;

    @GuardedBy("this")
    final Queue<ReferenceEntry<K, V>> accessQueue;
    
    Segment(LocalCache<K, V> map, int initialCapacity, long maxSegmentWeight, StatsCounter statsCounter) {
        // LocalCache 对象的引用
        this.map = map;
        // 最大分段权重，我们的例子中它的值是 250
        this.maxSegmentWeight = maxSegmentWeight;
        this.statsCounter = checkNotNull(statsCounter);
        // 根据初始化容量创建支持原子操作的 AtomicReferenceArray 对象
        initTable(newEntryArray(initialCapacity));

        // 管理弱引用和虚引用的 Key,Value 队列
        keyReferenceQueue = map.usesKeyReferences() ? new ReferenceQueue<>() : null;
        valueReferenceQueue = map.usesValueReferences() ? new ReferenceQueue<>() : null;

        // 用于记录最近元素被访问的顺序
        recencyQueue = map.usesAccessQueue() ? new ConcurrentLinkedQueue<>() : LocalCache.discardingQueue();

        // 用于记录元素的写入顺序，元素被写入时会被添加到尾部
        writeQueue = map.usesWriteQueue() ? new WriteQueue<>() : LocalCache.discardingQueue();

        // 记录元素的访问顺序，元素被访问后会被添加到尾部
        accessQueue = map.usesAccessQueue() ? new AccessQueue<>() : LocalCache.discardingQueue();
    }

    AtomicReferenceArray<ReferenceEntry<K, V>> newEntryArray(int size) {
        return new AtomicReferenceArray<>(size);
    }

    void initTable(AtomicReferenceArray<ReferenceEntry<K, V>> newTable) {
        // 默认负载因子为 0.75
        this.threshold = newTable.length() * 3 / 4;
        if (!map.customWeigher() && this.threshold == maxSegmentWeight) {
            // 在执行驱逐操作前防止不必要的扩张操作，将阈值+1
            this.threshold++;
        }
        this.table = newTable;
    }
    
    // ...
}
```

根据上述代码和注释信息，以我们创建的最大容量为 1000，过期时间为 10s 的 `LoadingCache` 为例，实际上它会将 1000 的最大容量分为 4 个段，创建 4 个 `Segment` 对象，每个 `Segment` 的数据结构由 `AtomicReferenceArray`（本质上是 `Object[]` 数组）和三个基于LRU算法的队列组成，`AtomicReferenceArray` 初始化为一个较小的容量（4），根据元素添加的情况会触发扩容，数据结构如下图所示：



