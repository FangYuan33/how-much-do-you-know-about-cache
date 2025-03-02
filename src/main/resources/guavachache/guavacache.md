

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

**Segments** 内部维护了缓存本身（`final LocalCache<K, V> map`），所以它能一直保持数据一致性，因此可以在不加锁的情况下进行读操作。键值对对象中的 `next` 字段被 `final` 修饰，所有的列表添加操作都只能在每个桶的前面进行，这也就使得检查变更比较容易，并且遍历速度也比较快。当节点需要更改时，会创建新节点来替换它们。这对于哈希表来说效果很好，因为桶列表往往很短（平均长度小于二）。

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

        // 用于记录“最近”元素被访问的顺序
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



### put

在深入 `put` 方法前，我们需要先了解下创建键值对元素的逻辑。在调用构造方法的逻辑时，其中 `entryFactory` 字段我们没具体讲解，在这里详细描述下，因为它与键值对的创建有关。`EntryFactory` 是一个枚举类，它其中定义了如 `STRONG_ACCESS_WRITE` 和 `WEAK_ACCESS_WRITE` 等一系列枚举，并根据创建缓存时指定的 `Key` 和 `Value` 引用类型来决定具体是哪个枚举，如其中的 `EntryFactory#getFactory` 方法所示：

```java
enum EntryFactory {
    STRONG {
        // ...
    },
    STRONG_ACCESS {
        // ...
    },
    STRONG_WRITE {
        // ...  
    },
    STRONG_ACCESS_WRITE {
        // ...
    },
    // ...
    WEAK_ACCESS_WRITE {
        // ...
    };

    static final int ACCESS_MASK = 1;
    static final int WRITE_MASK = 2;
    static final int WEAK_MASK = 4;
    
    static final EntryFactory[] factories = {
            STRONG,
            STRONG_ACCESS,
            STRONG_WRITE,
            STRONG_ACCESS_WRITE,
            WEAK,
            WEAK_ACCESS,
            WEAK_WRITE,
            WEAK_ACCESS_WRITE,
    };

    static EntryFactory getFactory(Strength keyStrength, boolean usesAccessQueue, boolean usesWriteQueue) {
        int flags = ((keyStrength == Strength.WEAK) ? WEAK_MASK : 0)
                        | (usesAccessQueue ? ACCESS_MASK : 0)
                        | (usesWriteQueue ? WRITE_MASK : 0);
        return factories[flags];
    }
}
```

当不指定 `Key` 和 `Value` 的引用类型时（`Key` 和 `Value` 均为强引用），**默认为 `STRONG_ACCESS_WRITE` 枚举**，我们主要关注下它的逻辑：

```java
enum EntryFactory {
    STRONG_ACCESS_WRITE {
        @Override
        <K, V> ReferenceEntry<K, V> newEntry(
                Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
            return new StrongAccessWriteEntry<>(key, hash, next);
        }

        // ...
    }
}
```

其中 `newEntry` 为创建键值对元素的方法，创建的键值对类型为 `StrongAccessWriteEntry`，我们看下它的具体实现：

```java
class LocalCache<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    
    static class StrongEntry<K, V> extends AbstractReferenceEntry<K, V> {
        
        final K key;
        final int hash;
        @CheckForNull final ReferenceEntry<K, V> next;
        volatile ValueReference<K, V> valueReference = unset();
        
        StrongEntry(K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.next = next;
        }
        
        // ...
    }

    static final class StrongAccessWriteEntry<K, V> extends StrongEntry<K, V> {

        volatile long accessTime = Long.MAX_VALUE;
        volatile long writeTime = Long.MAX_VALUE;

        @Weak ReferenceEntry<K, V> nextAccess = nullEntry();
        @Weak ReferenceEntry<K, V> previousAccess = nullEntry();

        @Weak ReferenceEntry<K, V> nextWrite = nullEntry();
        @Weak ReferenceEntry<K, V> previousWrite = nullEntry();

        StrongAccessWriteEntry(K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
            super(key, hash, next);
        }
    }
}
```

`StrongAccessWriteEntry` 与它的父类 `StrongEntry` 均为定义在 `LocalCache` 内的静态内部类，构造方法需要指定 `Key, hash, next` 三个变量，这三个变量均定义在 `StrongEntry` 中，注意第三个变量 `ReferenceEntry<K, V> next`，它被父类中 `StrongEntry#next` 字段引用，并且该字段被 `final` 修饰，这与前文 JavaDoc 中所描述的内容相对应：

> 键值对对象中的 `next` 字段被 `final` 修饰，所有的列表添加操作都只能在每个桶的前面进行，这也就使得检查变更比较容易，并且遍历速度也比较快。

所以实际上在添加新的键值对元素时，针对每个桶中元素操作采用的是“头插法”，这些元素是通过 `next` 指针引用的 **单向链表**。现在了解了元素的类型和创建逻辑，我们再来看下 `put` 方法的实现。

Guava Cache 中是不允许添加 null 键和 null 值的，如果添加了 null 键或 null 值，会抛出 `NullPointerException` 异常：

```java
class LocalCache<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    
    // ...

    final Segment<K, V>[] segments;

    final int segmentShift;

    final int segmentMask;

    @GuardedBy("this")
    long totalWeight;
    
    @CheckForNull
    @CanIgnoreReturnValue
    @Override
    public V put(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        // 计算 hash 值，过程中调用了 rehash 扰动函数使 hash 更均匀
        int hash = hash(key);
        // 根据 hash 值路由到具体的 Segment 段，再调用 Segment 的 put 方法
        return segmentFor(hash).put(key, hash, value, false);
    }

    Segment<K, V> segmentFor(int hash) {
        // 位移并位与运算，segmentMask 为段数组长度减一，保证计算结果在有效范围内
        return segments[(hash >>> segmentShift) & segmentMask];
    }
}
```

注意其中的注解 `@GuardedBy` 表示某方法或字段被调用或访问时需要持有哪个同步锁，在 Caffeine 中也有类似的应用。

```java
static class Segment<K, V> extends ReentrantLock {

    final AtomicInteger readCount = new AtomicInteger();

    @GuardedBy("this")
    final Queue<ReferenceEntry<K, V>> accessQueue;
    final Queue<ReferenceEntry<K, V>> recencyQueue;
    @GuardedBy("this")
    final Queue<ReferenceEntry<K, V>> writeQueue;

    // guava cache 本身
    @Weak
    final LocalCache<K, V> map;

    // Segment 中保存元素的数组
    @CheckForNull
    volatile AtomicReferenceArray<ReferenceEntry<K, V>> table;

    // 计数器
    final StatsCounter statsCounter;

    // 该段中的元素数量
    volatile int count;

    // 总的权重，不配置也表示元素数量，每个元素的权重为 1
    @GuardedBy("this")
    long totalWeight;

    // capacity * 0.75
    int threshold;

    @CanIgnoreReturnValue
    @CheckForNull
    V put(K key, int hash, V value, boolean onlyIfAbsent) {
        // 先加锁 ReentrantLock#lock 实现
        lock();
        try {
            long now = map.ticker.read();
            // 1. 写前置的清理操作，包括处理过期元素等
            preWriteCleanup(now);

            int newCount = this.count + 1;
            // 2. 如果超过阈值，则扩容
            if (newCount > this.threshold) {
                // 扩容操作
                expand();
                newCount = this.count + 1;
            }

            AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
            int index = hash & (table.length() - 1);
            ReferenceEntry<K, V> first = table.get(index);

            // Look for an existing entry.
            for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                K entryKey = e.getKey();
                if (e.getHash() == hash
                        && entryKey != null
                        && map.keyEquivalence.equivalent(key, entryKey)) {
                    // We found an existing entry.

                    ValueReference<K, V> valueReference = e.getValueReference();
                    V entryValue = valueReference.get();

                    if (entryValue == null) {
                        ++modCount;
                        if (valueReference.isActive()) {
                            enqueueNotification(
                                    key, hash, entryValue, valueReference.getWeight(), RemovalCause.COLLECTED);
                            setValue(e, key, value, now);
                            newCount = this.count; // count remains unchanged
                        } else {
                            setValue(e, key, value, now);
                            newCount = this.count + 1;
                        }
                        this.count = newCount; // write-volatile
                        evictEntries(e);
                        return null;
                    } else if (onlyIfAbsent) {
                        // Mimic
                        // "if (!map.containsKey(key)) ...
                        // else return map.get(key);
                        recordLockedRead(e, now);
                        return entryValue;
                    } else {
                        // clobber existing entry, count remains unchanged
                        ++modCount;
                        enqueueNotification(
                                key, hash, entryValue, valueReference.getWeight(), RemovalCause.REPLACED);
                        setValue(e, key, value, now);
                        evictEntries(e);
                        return entryValue;
                    }
                }
            }

            // Create a new entry.
            ++modCount;
            ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
            setValue(newEntry, key, value, now);
            table.set(index, newEntry);
            newCount = this.count + 1;
            this.count = newCount; // write-volatile
            evictEntries(newEntry);
            return null;
        } finally {
            unlock();
            postWriteCleanup();
        }
    }
}

```

#### preWriteCleanup

```java
static class Segment<K, V> extends ReentrantLock {

    final AtomicInteger readCount = new AtomicInteger();

    @GuardedBy("this")
    final Queue<ReferenceEntry<K, V>> accessQueue;
    final Queue<ReferenceEntry<K, V>> recencyQueue;
    @GuardedBy("this")
    final Queue<ReferenceEntry<K, V>> writeQueue;

    // guava cache 本身
    @Weak final LocalCache<K, V> map;

    // Segment 中保存元素的数组
    @CheckForNull volatile AtomicReferenceArray<ReferenceEntry<K, V>> table;

    // 计数器
    final StatsCounter statsCounter;

    // 该段中的元素数量
    volatile int count;

    // 总的权重，不配置也表示元素数量，每个元素的权重为 1
    @GuardedBy("this")
    long totalWeight;
    
    @GuardedBy("this")
    void preWriteCleanup(long now) {
        // 执行加锁的清理操作，包括处理引用队列和过期元素
        runLockedCleanup(now);
    }

    void runLockedCleanup(long now) {
        // 仍然是 ReentrantLock#tryLock 实现
        if (tryLock()) {
            try {
                // 处理引用队列（本文不对指定 Key 或 Value 为 weekReference 的情况进行分析）
                drainReferenceQueues();
                // 处理元素过期
                expireEntries(now);
                // 写后读次数清零
                readCount.set(0);
            } finally {
                unlock();
            }
        }
    }

    @GuardedBy("this")
    void expireEntries(long now) {
        // 处理最近访问队列 recencyQueue 和访问队列 accessQueue
        drainRecencyQueue();

        ReferenceEntry<K, V> e;
        // 从头节点开始遍历写队列 writeQueue，将过期的元素移除
        while ((e = writeQueue.peek()) != null && map.isExpired(e, now)) {
            if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
                throw new AssertionError();
            }
        }
        while ((e = accessQueue.peek()) != null && map.isExpired(e, now)) {
            if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
                throw new AssertionError();
            }
        }
    }

    // 将元素的最近被访问队列 recencyQueue 清空，并使用尾插法将它们都放到访问队列 accessQueue 的尾节点
    @GuardedBy("this")
    void drainRecencyQueue() {
        ReferenceEntry<K, V> e;
        // 遍历元素最近被访问队列 recencyQueue
        while ((e = recencyQueue.poll()) != null) {
            // 如果该元素在访问队列 accessQueue 中
            if (accessQueue.contains(e)) {
                // 则将其放到尾节点
                accessQueue.add(e);
            }
            // 源码中对这里的 IF 判断，标注了如下内容来解释如此判断的原因：
            // 尽管元素已经在缓存中删除，但它仍可能在 recencyQueue 队列中。
            // 这种情况可能出现在开发者操作元素删除或清空段中所有元素的同时执行读操作
        }
    }

    @VisibleForTesting
    @GuardedBy("this")
    @CanIgnoreReturnValue
    boolean removeEntry(ReferenceEntry<K, V> entry, int hash, RemovalCause cause) {
        int newCount = this.count - 1;
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        // 位与运算找到对应的桶，获取头节点
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
            // 找到了对应的元素则操作移除
            if (e == entry) {
                ++modCount;
                // 在链表chain中移除元素，注意 e 表示待移除的元素
                ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, e.getKey(), hash, 
                        e.getValueReference().get(), e.getValueReference(), cause);
                // 注意这里又重新计算了 newCount，防止在方法执行前的 newCount 快照值发生变更
                newCount = this.count - 1;
                // 桶的位置更新为新的链表头节点
                table.set(index, newFirst);
                // count 被 volatile 修饰，可见性写操作
                this.count = newCount;
                return true;
            }
        }

        return false;
    }


    /**
     * 将元素从队列中移除
     * 
     * @param first 队列的头节点
     * @param entry 待移除元素
     * @param key   待移除元素的 key
     * @param hash  待移除元素的 hash 值
     * @param value 待移除元素的 value 值
     * @param valueReference 带移除元素的 value 引用对象
     * @param cause 被移除的原因
     * @return 返回链表头节点
     */
    @GuardedBy("this")
    @CheckForNull
    ReferenceEntry<K, V> removeValueFromChain(ReferenceEntry<K, V> first, ReferenceEntry<K, V> entry, 
                                              @CheckForNull K key, int hash, V value, ValueReference<K, V> valueReference, 
                                              RemovalCause cause) {
        // 入队元素被移除的通知等操作
        enqueueNotification(key, hash, value, valueReference.getWeight(), cause);
        // 在写队列和访问队列中移除元素
        writeQueue.remove(entry);
        accessQueue.remove(entry);

        if (valueReference.isLoading()) {
            valueReference.notifyNewValue(null);
            return first;
        } else {
            // 将元素在链表中移除
            return removeEntryFromChain(first, entry);
        }
    }
    
    @GuardedBy("this")
    @CheckForNull
    ReferenceEntry<K, V> removeEntryFromChain(ReferenceEntry<K, V> first, ReferenceEntry<K, V> entry) {
        // 初始化计数跟踪数量变化
        int newCount = count;
        // 被移除元素的 next 元素，作为新的头节点
        ReferenceEntry<K, V> newFirst = entry.getNext();
        // 遍历从 原头节点 first 到 被移除元素 entry 之间的所有元素
        for (ReferenceEntry<K, V> e = first; e != entry; e = e.getNext()) {
            // 创建一个新的节点，该节点是节点 e 的副本，并把新节点的 next 指针指向 newFirst 节点
            ReferenceEntry<K, V> next = copyEntry(e, newFirst);
            // 如果 next 不为空，变更 newFirst 的引用，指向刚刚创建的节点
            // 如果原链表为 a -> b -> c -> d 移除 c 后链表为 b -> a -> d
            if (next != null) {
                newFirst = next;
            } else {
                // 如果 next 为空，表示发生了垃圾回收，将该被回收的元素的移除
                removeCollectedEntry(e);
                // 计数减一
                newCount--;
            }
        }
        // 更新计数
        this.count = newCount;
        // 返回新的头节点
        return newFirst;
    }

    @GuardedBy("this")
    void removeCollectedEntry(ReferenceEntry<K, V> entry) {
        // 入队元素被移除的通知等操作
        enqueueNotification(entry.getKey(), entry.getHash(), entry.getValueReference().get(),
                entry.getValueReference().getWeight(), RemovalCause.COLLECTED);
        writeQueue.remove(entry);
        accessQueue.remove(entry);
    }

    @GuardedBy("this")
    void enqueueNotification(@CheckForNull K key, int hash, @CheckForNull V value, int weight, RemovalCause cause) {
        // 将当前元素的权重在总权重中减去
        totalWeight -= weight;
        // 如果元素被驱逐，则在计数器中记录
        if (cause.wasEvicted()) {
            statsCounter.recordEviction();
        }
        // 如果配置了驱逐通知，则将该元素被驱逐的原因等信息放入队列
        if (map.removalNotificationQueue != DISCARDING_QUEUE) {
            RemovalNotification<K, V> notification = RemovalNotification.create(key, value, cause);
            map.removalNotificationQueue.offer(notification);
        }
    }
}

class LocalCache<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    // 判断元素是否过期，它的逻辑非常简单，如果配置了对应的过期模式，如访问后过期或写后过期
    // 会根据当前时间与元素的访问时间或写入时间进行比较，如果超过配置的过期时间，则返回 true，否则返回 false
    boolean isExpired(ReferenceEntry<K, V> entry, long now) {
        checkNotNull(entry);
        if (expiresAfterAccess() && (now - entry.getAccessTime() >= expireAfterAccessNanos)) {
            return true;
        }
        if (expiresAfterWrite() && (now - entry.getWriteTime() >= expireAfterWriteNanos)) {
            return true;
        }
        return false;
    }

}
```

#### expand

```java
static class Segment<K, V> extends ReentrantLock {

    static final int MAXIMUM_CAPACITY = 1 << 30;

    // Segment 中保存元素的数组
    @CheckForNull
    volatile AtomicReferenceArray<ReferenceEntry<K, V>> table;

    // 该段中的元素数量
    volatile int count;

    // 总的权重，不配置也表示元素数量，每个元素的权重为 1
    @GuardedBy("this")
    long totalWeight;

    // capacity * 0.75
    int threshold;

    @GuardedBy("this")
    void expand() {
        AtomicReferenceArray<ReferenceEntry<K, V>> oldTable = table;
        int oldCapacity = oldTable.length();
        // 如果容量已经达到最大值，则直接返回
        if (oldCapacity >= MAXIMUM_CAPACITY) {
            return;
        }

        int newCount = count;
        // 创建一个原来两倍容量的 AtomicReferenceArray
        AtomicReferenceArray<ReferenceEntry<K, V>> newTable = newEntryArray(oldCapacity << 1);
        // 阈值计算，负载因子为固定的 0.75
        threshold = newTable.length() * 3 / 4;
        // 掩码值为容量大小 -1，因为容量是 2 的幂次方，所以掩码值的二进制表示中，从最低位开始，所有位都是 1，位与运算能计算出元素对应的索引
        int newMask = newTable.length() - 1;
        // 遍历扩容前的 AtomicReferenceArray table
        for (int oldIndex = 0; oldIndex < oldCapacity; ++oldIndex) {
            ReferenceEntry<K, V> head = oldTable.get(oldIndex);

            if (head != null) {
                // 获取头节点的 next 节点
                ReferenceEntry<K, V> next = head.getNext();
                // 计算头节点在新数组中的索引
                int headIndex = head.getHash() & newMask;

                // 头节点的 next 节点为空，那么证明该桶位置只有一个元素，直接将头节点放在新数组的索引处 
                if (next == null) {
                    newTable.set(headIndex, head);
                } else {
                    // 遍历从 next 开始的节点，找到所有能 hash 到同一个桶的一批节点，然后将这些节点封装在新数组的对应索引处
                    ReferenceEntry<K, V> tail = head;
                    int tailIndex = headIndex;
                    for (ReferenceEntry<K, V> e = next; e != null; e = e.getNext()) {
                        int newIndex = e.getHash() & newMask;
                        if (newIndex != tailIndex) {
                            tailIndex = newIndex;
                            tail = e;
                        }
                    }
                    newTable.set(tailIndex, tail);

                    // 将这些剩余的不能 hash 到同一个桶的节点，依次遍历，将它们放入新数组中
                    for (ReferenceEntry<K, V> e = head; e != tail; e = e.getNext()) {
                        int newIndex = e.getHash() & newMask;
                        ReferenceEntry<K, V> newNext = newTable.get(newIndex);
                        // 注意这里创建节点是深拷贝，并且采用的是头插法
                        ReferenceEntry<K, V> newFirst = copyEntry(e, newNext);
                        if (newFirst != null) {
                            newTable.set(newIndex, newFirst);
                        } else {
                            // 如果 next 为空，表示发生了垃圾回收，将该被回收的元素的移除
                            removeCollectedEntry(e);
                            newCount--;
                        }
                    }
                }
            }
        }
        
        // 操作完成后更新 table 和 count
        table = newTable;
        this.count = newCount;
    }
    
}
```