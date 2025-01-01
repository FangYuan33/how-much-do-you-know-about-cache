我们先以简单的创建一个固定大小的缓存为例

```java
public class TestReadSourceCode {

    @Test
    public void doRead() {
        // read constructor
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .build();

        // read put
        cache.put("key", "value");

        // read get
        cache.getIfPresent("key");
    }

}
```

### constructor

在 Caffeine 的构造方法中，区分了 `BoundedLocalManualCache` 和 `UnboundedLocalManualCache`
，见名知意它们分别为有“边界”的和无“边界”的缓存，`isBounded` 方法诠释了“边界”的含义：

```java
public final class Caffeine<K, V> {

    static final int UNSET_INT = -1;

    public <K1 extends K, V1 extends V> Cache<K1, V1> build() {
        // 校验参数
        requireWeightWithWeigher();
        requireNonLoadingCache();

        @SuppressWarnings("unchecked")
        Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
        return isBounded()
                ? new BoundedLocalCache.BoundedLocalManualCache<>(self)
                : new UnboundedLocalCache.UnboundedLocalManualCache<>(self);
    }

    boolean isBounded() {
        // 指定了最大大小；指定了最大权重
        return (maximumSize != UNSET_INT) || (maximumWeight != UNSET_INT)
                // 指定了访问后过期策略；指定了写后过期策略
                || (expireAfterAccessNanos != UNSET_INT) || (expireAfterWriteNanos != UNSET_INT)
                // 指定了自定义过期策略；指定了 key 或 value 的引用级别
                || (expiry != null) || (keyStrength != null) || (valueStrength != null);
    }
}
```

也就是说，当为缓存指定了上述的驱逐或过期策略会定义为有边界的 `BoundedLocalManualCache`
缓存，它会限制缓存的大小，防止内存溢出，否则为无边界的 `UnboundedLocalManualCache`
缓存，它没有大小限制，直到内存耗尽。`UnboundedLocalManualCache`
实现相对简单，本文不会对它进行介绍，会主要关注 `BoundedLocalManualCache`，它在执行构造方法时，有以下逻辑：

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef
        implements LocalCache<K, V> {
    // ...

    static class BoundedLocalManualCache<K, V> implements LocalManualCache<K, V>, Serializable {
        private static final long serialVersionUID = 1;

        final BoundedLocalCache<K, V> cache;

        BoundedLocalManualCache(Caffeine<K, V> builder) {
            this(builder, null);
        }

        BoundedLocalManualCache(Caffeine<K, V> builder, @Nullable CacheLoader<? super K, V> loader) {
            cache = LocalCacheFactory.newBoundedLocalCache(builder, loader, /* async */ false);
        }
    }
}
```

我们可以发现 `BoundedLocalCache` 为抽象类，创建对象的实际类型应该是它的子类，而且它在创建时，使用了反射并遵循简单工厂的编码风格：

```java
interface LocalCacheFactory {
    static <K, V> BoundedLocalCache<K, V> newBoundedLocalCache(Caffeine<K, V> builder,
                                                               @Nullable AsyncCacheLoader<? super K, V> cacheLoader, boolean async) {
        var className = getClassName(builder);
        var factory = loadFactory(className);
        try {
            return factory.newInstance(builder, cacheLoader, async);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(className, t);
        }
    }
}
```

`getClassName` 非常有意思，它会根据为缓存设置的一些属性动态的拼接出列名：

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

这也就是为什么能在 `com.github.benmanes.caffeine.cache` 包路径下能发现很多类似 `SSMW` 只有简称命名的类（下图只截取部分，实际上有很多）：

![img.png](SSMS.png)

根据代码，它的命名遵循如下格式 `S|W S|I [L] [S] [MW|MS] [A] [W] [R]` 其中 `[]` 表示选填 `|`
为某位置不同选择的分隔符，结合注释能清楚的了解各个位置字母表达的含义。如此定义使用了多级继承，尽可能多地复用代码，以我们测试用例中创建的 `SSMS`
为例，它表示 key 和 value 均为强引用并且配置了非权重的最大缓存大小，类图关系如下：

![img.png](SSMS.drawio.png)

虽然在一些软件设计相关的书籍中强调“多用组合，少用继承”，但是这里使用多级继承我觉得并没有增加开发者的理解难度，反而了解了它的命名规则后，能更清晰的理解各个缓存所表示的含义，实现代码复用。除了缓存的定义遵循这样的命名规则，节点类的定义也是用了这种方式，如下：

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

它的命名遵循 `P|F S|W|D A|AW|W| [R] [MW|MS]` 的规则，在后文中创建节点时，便不再对此进行赘述了。接下来我们回到 `SSMS`
类型缓存的构造方法逻辑中，它会依次执行如下逻辑：

```java
// 1
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef
        implements LocalCache<K, V> {

    static final int WRITE_BUFFER_MIN = 4;
    static final int WRITE_BUFFER_MAX = 128 * ceilingPowerOfTwo(NCPU);

    static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;

    static final double PERCENT_MAIN = 0.99d;
    static final double PERCENT_MAIN_PROTECTED = 0.80d;

    static final double HILL_CLIMBER_STEP_PERCENT = 0.0625d;

    final @Nullable RemovalListener<K, V> evictionListener;
    final @Nullable AsyncCacheLoader<K, V> cacheLoader;

    final MpscGrowableArrayQueue<Runnable> writeBuffer;
    final ConcurrentHashMap<Object, Node<K, V>> data;
    final PerformCleanupTask drainBuffersTask;
    final Consumer<Node<K, V>> accessPolicy;
    final Buffer<Node<K, V>> readBuffer;
    final NodeFactory<K, V> nodeFactory;
    final ReentrantLock evictionLock;
    final Weigher<K, V> weigher;
    final Executor executor;

    final boolean isAsync;
    final boolean isWeighted;

    protected BoundedLocalCache(Caffeine<K, V> builder,
                                @Nullable AsyncCacheLoader<K, V> cacheLoader, boolean isAsync) {
        // 标记同步或异步
        this.isAsync = isAsync;
        // 指定 cacheLoader 
        this.cacheLoader = cacheLoader;
        // 指定用于执行驱逐元素、刷新缓存等任务的线程池，不指定默认为 ForkJoinPool.commonPool()
        executor = builder.getExecutor();
        // 标记是否定义了节点计算权重的 Weigher 对象
        isWeighted = builder.isWeighted();
        // 同步锁，在接下来的内容中会看到很多标记了 @GuardedBy("evictionLock") 注解的方法，表示这行这些方法时都会获取这把同步锁
        evictionLock = new ReentrantLock();
        // 计算元素权重的对象，不指定为 SingletonWeigher.INSTANCE
        weigher = builder.getWeigher(isAsync);
        // 执行缓存 maintenance 方法的任务，在后文中具体介绍
        drainBuffersTask = new PerformCleanupTask(this);
        // 创建节点的工厂
        nodeFactory = NodeFactory.newFactory(builder, isAsync);
        // 驱逐监听器，有元素被驱逐时会回调
        evictionListener = builder.getEvictionListener(isAsync);
        // 用于保存所有数据的 ConcurrentHashMap
        data = new ConcurrentHashMap<>(builder.getInitialCapacity());
        // 如果指定驱逐策略 或 key为弱引用 或 value为弱引用 或 访问后过期则创建 readBuffer，否则它为不可用状态
        // readBuffer 用于记录某些被访问过的节点，这些节点
        readBuffer = evicts() || collectKeys() || collectValues() || expiresAfterAccess()
                ? new BoundedBuffer<>() : Buffer.disabled();
        // 如果指定了驱逐策略 或 访问后过期策略则会定义访问策略，执行 onAccess 方法，后文详细介绍
        accessPolicy = (evicts() || expiresAfterAccess()) ? this::onAccess : e -> {
        };
        // 初始化最大值和最小值的双端队列作为 writeBuffer，用于记录一些写后操作任务 
        writeBuffer = new MpscGrowableArrayQueue<>(WRITE_BUFFER_MIN, WRITE_BUFFER_MAX);

        // 执行了驱逐策略则更新最大容量限制
        if (evicts()) {
            setMaximumSize(builder.getMaximum());
        }
    }

    @GuardedBy("evictionLock")
    void setMaximumSize(long maximum) {
        requireArgument(maximum >= 0, "maximum must not be negative");
        if (maximum == maximum()) {
            return;
        }

        // 不能超过最大容量
        long max = Math.min(maximum, MAXIMUM_CAPACITY);
        // 计算窗口区大小
        long window = max - (long) (PERCENT_MAIN * max);
        // 计算保护区大小
        long mainProtected = (long) (PERCENT_MAIN_PROTECTED * (max - window));

        // 记录这些值
        setMaximum(max);
        setWindowMaximum(window);
        setMainProtectedMaximum(mainProtected);

        // 标记命中量、非命中量并初始化步长值，这三个值用于后续动态调整保护区和窗口区大小
        setHitsInSample(0);
        setMissesInSample(0);
        setStepSize(-HILL_CLIMBER_STEP_PERCENT * max);

        // 直到当前缓存的权重（大小）接近最大值时才初始化频率草图
        if ((frequencySketch() != null) && !isWeighted() && (weightedSize() >= (max >>> 1))) {
            frequencySketch().ensureCapacity(max);
        }
    }
}

// 2
class SS<K, V> extends BoundedLocalCache<K, V> {
    static final LocalCacheFactory FACTORY = SS::new;

    // key value 强引用无需特殊操作
    SS(Caffeine<K, V> var1, @Nullable AsyncCacheLoader<? super K, V> var2, boolean var3) {
        super(var1, var2, var3);
    }
}

// 3
class SSMS<K, V> extends SS<K, V> {

    final FrequencySketch<K> sketch = new FrequencySketch();

    final AccessOrderDeque<Node<K, V>> accessOrderWindowDeque;
    final AccessOrderDeque<Node<K, V>> accessOrderProbationDeque;
    final AccessOrderDeque<Node<K, V>> accessOrderProtectedDeque;

    SSMS(Caffeine<K, V> var1, @Nullable AsyncCacheLoader<? super K, V> var2, boolean var3) {
        super(var1, var2, var3);
        // 如果 caffeine 初始化了容量则确定频率草图的容量
        if (var1.hasInitialCapacity()) {
            long var4 = Math.min(var1.getMaximum(), (long) var1.getInitialCapacity());
            this.sketch.ensureCapacity(var4);
        }

        // 初始化窗口区、试用区和保护区，它们都是双端队列（链表实现）
        this.accessOrderWindowDeque = !var1.evicts() && !var1.expiresAfterAccess() ? null : new AccessOrderDeque();
        this.accessOrderProbationDeque = new AccessOrderDeque();
        this.accessOrderProtectedDeque = new AccessOrderDeque();
    }
}
```

> 注释中描述的驱逐或访问过期策略可以在创建 `Caffeine` 缓存时指定，指定最大缓存容量也是缓存的驱逐策略之一。

在步骤 1 中我们需要解释一下 `weightedSize()` 方法，它用于访问 `long weightedSize`
变量。根据其命名有“权重大小”的含义，在默认不指定权重计算对象 `Weigher` 的情况下，`Weigher`
默认为 `SingletonWeigher.INSTANCE` 表示每个元素的权重大小为 1，如下：

```java
enum SingletonWeigher implements Weigher<Object, Object> {
    INSTANCE;

    @Override
    public int weigh(Object key, Object value) {
        return 1;
    }
}
```

这样 `weightedSize` 表示的便是当前缓存中元素数量，如果自定义了 `Weigher` 那么 `weightedSize`
表示的便是缓存中总权重大小，每个元素的权重则可能会不同。因为在示例中我们并没有指定 `Weigher`
，所以在此处可以将 `weightedSize` 理解为当前缓存大小。

除此之外我们还需要具体介绍下 `FrequencySketch`。这个类使用 **Count-Min Sketch**
算法计算某个元素的访问频率。它维护了一个 `long[] table` 一维数组，每个元素有 64 位，每 4 位作为一个计数器（这也就限定了最大频率为
15），那么数组中每个槽位便是 16 个计数器。通过哈希函数取 4 个独立的计数值，将其中的最小值作为元素的访问频率。`table`
的初始大小为缓存最大容量最接近的 2 的 n 次幂，并在计算哈希值时使用 `blockMask` 掩码来使哈希结果均匀分布，保证了获取元素访问频率的正确率为
93.75%，达到空间与时间的平衡。它的实现原理和布隆过滤器类似，牺牲了部分准确性，但减少了占用内存的大小。如下图所示为计算元素 e
的访问频率：

![frequencySketch.drawio.png](frequencySketch.drawio.png)

以下为 `FrequencySketch` 的源码，关注注释即可，并不复杂：

```java
final class FrequencySketch<E> {

    static final long RESET_MASK = 0x7777777777777777L;
    static final long ONE_MASK = 0x1111111111111111L;

    // 采样大小，用于控制 reset
    int sampleSize;
    // 掩码，用于均匀分散哈希结果
    int blockMask;
    long[] table;
    int size;

    public FrequencySketch() {
    }

    public void ensureCapacity(@NonNegative long maximumSize) {
        requireArgument(maximumSize >= 0);
        // 取缓存最大容量和 Integer.MAX_VALUE >>> 1 中的小值 
        int maximum = (int) Math.min(maximumSize, Integer.MAX_VALUE >>> 1);
        // 如果已经被初始化过并且 table 长度大于等于最大容量，那么不进行操作
        if ((table != null) && (table.length >= maximum)) {
            return;
        }

        // 初始化 table，长度为最接近 maximum 的 2的n次幂和 8 中的大值
        table = new long[Math.max(Caffeine.ceilingPowerOfTwo(maximum), 8)];
        // 计算采样大小
        sampleSize = (maximumSize == 0) ? 10 : (10 * maximum);
        // 计算掩码
        blockMask = (table.length >>> 3) - 1;
        // 特殊判断
        if (sampleSize <= 0) {
            sampleSize = Integer.MAX_VALUE;
        }
        // 计数器总数
        size = 0;
    }

    @NonNegative
    public int frequency(E e) {
        // 如果缓存没有被初始化则返回频率为 0
        if (isNotInitialized()) {
            return 0;
        }

        // 创建 4 个元素的数组 count 用于保存 4 次 hash 计算出的频率值
        int[] count = new int[4];
        // hash 扰动，使结果均匀分布
        int blockHash = spread(e.hashCode());
        // 重 hash，进一步分散结果
        int counterHash = rehash(blockHash);
        // 根据掩码计算对应的块索引
        int block = (blockHash & blockMask) << 3;
        // 循环 4 次计算 4 个计数器的结果
        for (int i = 0; i < 4; i++) {
            // 位运算变更 hash 值
            int h = counterHash >>> (i << 3);
            int index = (h >>> 1) & 15;
            // 计算计数器的偏移量
            int offset = h & 1;
            // 定位到 table 中某个槽位后右移并进行位与运算得到最低的 4 位的值（0xfL 为二进制的 1111）
            count[i] = (int) ((table[block + offset + (i << 1)] >>> (index << 2)) & 0xfL);
        }
        // 取其中的较小值
        return Math.min(Math.min(count[0], count[1]), Math.min(count[2], count[3]));
    }

    public void increment(E e) {
        if (isNotInitialized()) {
            return;
        }

        // 长度为 8 的数组记录该元素对应的位置，每个计数器需要两个值来定位
        int[] index = new int[8];
        int blockHash = spread(e.hashCode());
        int counterHash = rehash(blockHash);
        int block = (blockHash & blockMask) << 3;
        for (int i = 0; i < 4; i++) {
            int h = counterHash >>> (i << 3);
            // i 记录定位到 table 中某元素的位偏移量
            index[i] = (h >>> 1) & 15;
            int offset = h & 1;
            // i + 4 记录元素所在 table 中的索引
            index[i + 4] = block + offset + (i << 1);
        }
        // 四个对应的计数器都需要累加
        boolean added =
                incrementAt(index[4], index[0])
                        | incrementAt(index[5], index[1])
                        | incrementAt(index[6], index[2])
                        | incrementAt(index[7], index[3]);

        // 累加成功且达到采样大小需要进行重置
        if (added && (++size == sampleSize)) {
            reset();
        }
    }

    boolean incrementAt(int i, int j) {
        int offset = j << 2;
        long mask = (0xfL << offset);
        if ((table[i] & mask) != mask) {
            table[i] += (1L << offset);
            return true;
        }
        return false;
    }

    // 重置机制防止计数器溢出
    void reset() {
        int count = 0;
        for (int i = 0; i < table.length; i++) {
            // 累加 table 中每个元素的 2 进制表示的 1 的个数，结果为计数器个数的 4 倍
            count += Long.bitCount(table[i] & ONE_MASK);
            // 右移一位将计数值减半并将高位清零
            table[i] = (table[i] >>> 1) & RESET_MASK;
        }
        // count >>> 2 表示计数器个数，计算重置后的 size
        size = (size - (count >>> 2)) >>> 1;
    }

    static int spread(int x) {
        x ^= x >>> 17;
        x *= 0xed5ad4bb;
        x ^= x >>> 11;
        x *= 0xac4c1b51;
        x ^= x >>> 15;
        return x;
    }

    static int rehash(int x) {
        x *= 0x31848bab;
        x ^= x >>> 14;
        return x;
    }

}
```

到这里，`Caffeine` 缓存的基本数据结构全貌已经展现出来了，如下所示，在后文中我们再具体讲解它们之间是如何协同的。

![caffeine.drawio.png](caffeine.drawio.png)

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

            // “增值” 操作，后续重点讲
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

在这个过程中我们简单了解了 `put` 添加缓存中不存在的元素的处理流程，需要知道元素是被直接添加到 `ConcurrentHashMap data`
中的，至于其他驱逐、过期等维护操作是由任务异步驱动完成的，而且只能由单线程去处理，至于其中详细的逻辑在后文中介绍。

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

    @Nullable
    V afterRead(Node<K, V> node, long now, boolean recordHit) {
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

### maintenance

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    @GuardedBy("evictionLock")
    void maintenance(@Nullable Runnable task) {
        // 更新状态为执行中
        setDrainStatusRelease(PROCESSING_TO_IDLE);

        try {
            // 1. 处理读缓冲区中的任务
            drainReadBuffer();

            // 2. 处理写缓冲区中的任务
            drainWriteBuffer();
            if (task != null) {
                task.run();
            }

            // 3. 处理 key 和 value 的引用
            drainKeyReferences();
            drainValueReferences();

            // 4. 过期和驱逐策略
            expireEntries();
            evictEntries();

            // 5. “增值” 操作
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

首先我们来看步骤一处理读缓冲区：

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    final Buffer<Node<K, V>> readBuffer;

    final Consumer<Node<K, V>> accessPolicy;

    @GuardedBy("evictionLock")
    void drainReadBuffer() {
        if (!skipReadBuffer()) {
            readBuffer.drainTo(accessPolicy);
        }
    }

}
```

它在这里会执行到 `BoundedBuffer#drainTo` 方法，并且入参了 `Consumer<Node<K, V>> accessPolicy`

```java
final class BoundedBuffer<E> extends StripedBuffer<E> {
    static final class RingBuffer<E> extends BBHeader.ReadAndWriteCounterRef implements Buffer<E> {
        static final VarHandle BUFFER = MethodHandles.arrayElementVarHandle(Object[].class);

        @Override
        public void drainTo(Consumer<E> consumer) {
            long head = readCounter;
            long tail = writeCounterOpaque();
            long size = (tail - head);
            if (size == 0) {
                return;
            }
            do {
                int index = (int) (head & MASK);
                @SuppressWarnings("unchecked")
                E e = (E) BUFFER.getAcquire(buffer, index);
                if (e == null) {
                    // not published yet
                    break;
                }
                BUFFER.setRelease(buffer, index, null);
                consumer.accept(e);
                head++;
            } while (head != tail);
            setReadCounterOpaque(head);
        }
    }
}
```

接下来我们看一下为 `accessPolicy` 赋值的逻辑

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    final Buffer<Node<K, V>> readBuffer;

    final Consumer<Node<K, V>> accessPolicy;

    protected BoundedLocalCache(Caffeine<K, V> builder,
                                @Nullable AsyncCacheLoader<K, V> cacheLoader, boolean isAsync) {
        accessPolicy = (evicts() || expiresAfterAccess()) ? this::onAccess : e -> {
        };
    }

    @GuardedBy("evictionLock")
    void onAccess(Node<K, V> node) {
        if (evicts()) {
            K key = node.getKey();
            if (key == null) {
                return;
            }
            // 更新访问频率
            frequencySketch().increment(key);
            // 根据节点所在位置执行对应的重排序方法
            if (node.inWindow()) {
                reorder(accessOrderWindowDeque(), node);
            }
            // 在试用区的节点执行 reorderProbation 方法，可能会将该节点从试用区晋升到保护区
            else if (node.inMainProbation()) {
                reorderProbation(node);
            } else {
                reorder(accessOrderProtectedDeque(), node);
            }
            setHitsInSample(hitsInSample() + 1);
        } else if (expiresAfterAccess()) {
            reorder(accessOrderWindowDeque(), node);
        }
        if (expiresVariable()) {
            timerWheel().reschedule(node);
        }
    }

    static <K, V> void reorder(LinkedDeque<Node<K, V>> deque, Node<K, V> node) {
        // 如果节点存在，将其移动到尾结点
        if (deque.contains(node)) {
            deque.moveToBack(node);
        }
    }

    @GuardedBy("evictionLock")
    void reorderProbation(Node<K, V> node) {
        // 检查试用区是否包含该节点，不包含则证明已经被移除，则不处理
        if (!accessOrderProbationDeque().contains(node)) {
            return;
        }
        // 检查节点的权重是否超过保护区最大值
        else if (node.getPolicyWeight() > mainProtectedMaximum()) {
            // 如果超过，将该节点移动到 试用区 尾巴节点，保证超重的节点不会被移动到保护区
            reorder(accessOrderProbationDeque(), node);
            return;
        }


        // 更新保护区权重大小
        setMainProtectedWeightedSize(mainProtectedWeightedSize() + node.getPolicyWeight());
        // 在试用区中移除该节点
        accessOrderProbationDeque().remove(node);
        // 在保护区尾节点中添加
        accessOrderProtectedDeque().offerLast(node);
        // 将该节点标记为保护区节点
        node.makeMainProtected();
    }
}
```

在这个方法中有一段注释非常重要，它说：

> If the protected space exceeds its maximum, the LRU items are demoted to the probation space.
> This is deferred to the adaption phase at the end of the maintenance cycle.

如果保护区空间超过它的最大值，它会将其中的元素降级到试用区。但是这个操作被推迟到 `maintenance` 方法的最后执行。

Main Probation: 试用区

Main Protected: 保护区

那么，我们在这里假设，执行 `maintenance` 方法时其他处理写缓冲区方法等均无需特别处理，直接跳转到最后的 `climb`
，看看它是如何为缓存“增值（climb）”的：

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    static final double HILL_CLIMBER_RESTART_THRESHOLD = 0.05d;

    static final double HILL_CLIMBER_STEP_PERCENT = 0.0625d;

    // 步长值衰减比率
    static final double HILL_CLIMBER_STEP_DECAY_RATE = 0.98d;

    static final int QUEUE_TRANSFER_THRESHOLD = 1_000;

    @GuardedBy("evictionLock")
    void climb() {
        if (!evicts()) {
            return;
        }

        // 确定要调整的量
        determineAdjustment();
        // 将保护区中的元素降级到试用区
        demoteFromMainProtected();
        // 获取第一步计算完毕的调整大小
        long amount = adjustment();
        // 不调整则结束，否则根据正负增大或减小窗口大小
        if (amount == 0) {
            return;
        } else if (amount > 0) {
            increaseWindow();
        } else {
            decreaseWindow();
        }
    }

    @GuardedBy("evictionLock")
    void determineAdjustment() {
        // 检查频率草图是否被初始化
        if (frequencySketch().isNotInitialized()) {
            // 没有被初始化则重置命中率、命中和未命中样本数
            setPreviousSampleHitRate(0.0);
            setMissesInSample(0);
            setHitsInSample(0);
            return;
        }

        // 请求总数 = 命中样本数 + 未命中样本数
        int requestCount = hitsInSample() + missesInSample();
        if (requestCount < frequencySketch().sampleSize) {
            return;
        }

        // 计算命中率、命中率变化
        double hitRate = (double) hitsInSample() / requestCount;
        double hitRateChange = hitRate - previousSampleHitRate();
        // 计算调整量，如果命中率增加获取正的步长值，否则获取负的步长值
        double amount = (hitRateChange >= 0) ? stepSize() : -stepSize();
        // 计算下一个步长值，如果变化量超过阈值，那么重新计算步长，否则按照固定衰减率计算
        double nextStepSize = (Math.abs(hitRateChange) >= HILL_CLIMBER_RESTART_THRESHOLD)
                ? HILL_CLIMBER_STEP_PERCENT * maximum() * (amount >= 0 ? 1 : -1)
                : HILL_CLIMBER_STEP_DECAY_RATE * amount;
        // 记录本次命中率作为下一次计算的依据
        setPreviousSampleHitRate(hitRate);
        // 记录要调整的量
        setAdjustment((long) amount);
        // 记录步长值
        setStepSize(nextStepSize);
        // 重置未命中和命中数量
        setMissesInSample(0);
        setHitsInSample(0);
    }

    @GuardedBy("evictionLock")
    void demoteFromMainProtected() {
        // 获取保护区的最大值和当前值
        long mainProtectedMaximum = mainProtectedMaximum();
        long mainProtectedWeightedSize = mainProtectedWeightedSize();
        // 当前值没有超过最大值则不处理
        if (mainProtectedWeightedSize <= mainProtectedMaximum) {
            return;
        }

        // 每次从保护区转换到试用区有 1000 个最大限制
        for (int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
            // 一旦不超过最大阈值则停止
            if (mainProtectedWeightedSize <= mainProtectedMaximum) {
                break;
            }

            // 在保护区取出头节点
            Node<K, V> demoted = accessOrderProtectedDeque().poll();
            if (demoted == null) {
                break;
            }
            // 标记为试用区
            demoted.makeMainProbation();
            // 加入到试用区中
            accessOrderProbationDeque().offerLast(demoted);
            // 计算保护区权重大小
            mainProtectedWeightedSize -= demoted.getPolicyWeight();
        }
        // 更新保护区权重
        setMainProtectedWeightedSize(mainProtectedWeightedSize);
    }

    @GuardedBy("evictionLock")
    void increaseWindow() {
        // 保护区最大容量为 0 则没有可调整的空间
        if (mainProtectedMaximum() == 0) {
            return;
        }

        // 窗口调整的变化量由保护区贡献，取能够变化额度 quota 为计算调整量和保护区最大值中的小值
        long quota = Math.min(adjustment(), mainProtectedMaximum());
        // 减小保护区大小增加窗口区大小
        setMainProtectedMaximum(mainProtectedMaximum() - quota);
        setWindowMaximum(windowMaximum() + quota);
        // 保护区大小变动后，需要操作元素由保护区降级到试用区
        demoteFromMainProtected();

        for (int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
            // 获取试用区头节点为“候选节点”
            Node<K, V> candidate = accessOrderProbationDeque().peekFirst();
            boolean probation = true;
            // 如果在试用区获取失败或者窗口调整的变化量要比该节点所占的权重小，那么尝试从保护区获取节点
            if ((candidate == null) || (quota < candidate.getPolicyWeight())) {
                candidate = accessOrderProtectedDeque().peekFirst();
                probation = false;
            }
            // 试用区和保护区均无节点，则无需处理，结束循环
            if (candidate == null) {
                break;
            }

            // 获取该候选节点的权重，如果可变化额度比候选权重小，那么无需处理
            int weight = candidate.getPolicyWeight();
            if (quota < weight) {
                break;
            }

            // 每移除一个节点更新需要可变化额度
            quota -= weight;
            // 如果是试用区节点，则直接移除
            if (probation) {
                accessOrderProbationDeque().remove(candidate);
            }
            // 如果是保护区节点，需要更新保护区权重大小，再将其从保护区中移除
            else {
                setMainProtectedWeightedSize(mainProtectedWeightedSize() - weight);
                accessOrderProtectedDeque().remove(candidate);
            }
            // 增加窗口区大小
            setWindowWeightedSize(windowWeightedSize() + weight);
            // 将被移除的“候选节点”添加到窗口区中
            accessOrderWindowDeque().offerLast(candidate);
            // 标记为窗口区节点
            candidate.makeWindow();
        }

        // 可能存在 quota 小于 节点权重 的情况，那么这些量无法再调整，需要重新累加到保护区，并在窗口区中减掉
        setMainProtectedMaximum(mainProtectedMaximum() + quota);
        setWindowMaximum(windowMaximum() - quota);
        // 将未完成调整的 quota 记录在调整值中
        setAdjustment(quota);
    }

    @GuardedBy("evictionLock")
    void decreaseWindow() {
        // 如果窗口区大小小于等于 1 则无法再减少了
        if (windowMaximum() <= 1) {
            return;
        }

        // 获取变化量的额度（正整数），取调整值和窗口最大值减一中较小的值
        long quota = Math.min(-adjustment(), Math.max(0, windowMaximum() - 1));
        // 更新保护区和窗口区大小
        setMainProtectedMaximum(mainProtectedMaximum() + quota);
        setWindowMaximum(windowMaximum() - quota);

        for (int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
            // 从窗口区获取“候选节点”
            Node<K, V> candidate = accessOrderWindowDeque().peekFirst();
            // 未获取到说明窗口区已经没有元素了，不能再减小了，结束循环操作
            if (candidate == null) {
                break;
            }

            // 获取候选节点的权重
            int weight = candidate.getPolicyWeight();
            // 可变化的额度小于权重，则不支持变化，结束循环
            if (quota < weight) {
                break;
            }

            // 随着节点的移动，变更可变化额度
            quota -= weight;
            // 更新窗口区大小并将元素从窗口区移除
            setWindowWeightedSize(windowWeightedSize() - weight);
            accessOrderWindowDeque().remove(candidate);
            // 将从窗口区中移除的元素添加到试用区
            accessOrderProbationDeque().offerLast(candidate);
            // 将节点标记为试用区元素
            candidate.makeMainProbation();
        }

        // 此时 quote 为剩余无法变更的额度，需要在保护区中减去在窗口区中加上
        setMainProtectedMaximum(mainProtectedMaximum() - quota);
        setWindowMaximum(windowMaximum() + quota);
        // 记录未变更完的额度在调整值中
        setAdjustment(-quota);
    }

}
```

- policy weight 的含义 与 policy 有关系吗？