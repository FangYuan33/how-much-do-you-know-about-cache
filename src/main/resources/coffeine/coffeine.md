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
缓存，它没有大小限制，直到内存耗尽。

接下来我们主要关注 `BoundedLocalManualCache`，它在执行构造方法时，有以下逻辑：

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

这也就是为什么能在 `com.github.benmanes.caffeine.cache` 包路径下能发现很多类似 `SSMS` 只有简称命名的类（下图只截取部分，实际上有很多）：

![img.png](SSMS.png)

根据代码，它的命名遵循如下格式 `S|W S|I [L] [S] [MW|MS] [A] [W] [R]` 其中 `[]` 表示选填 `|`
为某位置不同选择的分隔符，结合注释能清楚的了解各个位置字母表达的含义。如此定义使用了多级继承，尽可能多地复用代码，以我们测试用例中创建的 `SSMS`
为例，它表示 key 和 value 均为强引用并且配置了非权重的最大缓存大小，类图关系如下：

![img.png](SSMS.drawio.png)

虽然在一些软件设计相关的书籍中强调“多用组合，少用继承”，但是这里使用多级继承我觉得并没有增加开发者的理解难度，反而了解了它的命名规则后，能更清晰的理解各个缓存所表示的含义，实现代码复用。

测试样例创建的缓存类型为 `SSMS`，它的构造方法会依次执行如下逻辑：

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

    // 频率草图，后文具体介绍
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

还有一个点需要注意，上文中我们提到缓存的定义遵循大写字母缩写的命名规则，节点类的定义也是用了这种方式，在创建节点工厂 `NodeFactory.newFactory(builder, isAsync)`
的逻辑中，它会执行如下逻辑，根据缓存的类型来确定它的节点类型，命名遵循 `P|F S|W|D A|AW|W| [R] [MW|MS]` 的规则，同样使用了反射，如下：

```java
interface NodeFactory<K, V> {
    // ...

    static <K, V> NodeFactory<K, V> newFactory(Caffeine<K, V> builder, boolean isAsync) {
        if (builder.interner) {
            return (NodeFactory<K, V>) Interned.FACTORY;
        }
        var className = getClassName(builder, isAsync);
        return loadFactory(className);
    }

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

`SSMS` 类型缓存对应的节点类型为 `PSMS`。

除此之外我们还需要具体介绍下 `FrequencySketch`，它在上述方法的步骤 3 中被创建。这个类使用 **Count-Min Sketch**
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

到这里，`Caffeine` 缓存的基本数据结构全貌已经展现出来了，如下所示，在后文中我们再具体关注它们之间是如何协同的。

![caffeine.drawio.png](caffeine.drawio.png)

### put

接下来我们需要了解一下，向缓存中添加不存在的元素流程，如下为 Caffeine 的 `put` 方法：

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    // 默认入参 onlyIfAbsent 为 false，表示向缓存中添加相同的 key 会对 value 进行替换 
    @Override
    public @Nullable V put(K key, V value) {
        return put(key, value, expiry(), /* onlyIfAbsent */ false);
    }
}
```

它会执行到如下具体逻辑中，关注注释信息：

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    static final int WRITE_BUFFER_RETRIES = 100;

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
                // 如果 node 在循环执行中还未被创建
                if (node == null) {
                    // NodeFactory 创建对应类型节点
                    node = nodeFactory.newNode(key, keyReferenceQueue(), value, valueReferenceQueue(), newWeight, now);
                    // 设置节点的过期时间
                    setVariableTime(node, expireAfterCreate(key, value, expiry, now));
                }
                // 尝试添加新节点到缓存中，如果键已存在则返回现有节点
                prior = data.putIfAbsent(node.getKeyReference(), node);
                // 返回 null 表示插入成功
                if (prior == null) {
                    // 写操作后添加 AddTask 并调度执行任务
                    afterWrite(new AddTask(node, newWeight));
                    return null;
                }
                // ...
            }
            // ...
        }
        // ...
    }
}
```

注意添加节点成功的逻辑，添加成功会添加 `AddTask` 任务到 `writeBuffer` 中：

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    // 写重试最多 100 次
    static final int WRITE_BUFFER_RETRIES = 100;

    static final int WRITE_BUFFER_MIN = 4;
    static final int WRITE_BUFFER_MAX = 128 * ceilingPowerOfTwo(NCPU);

    final MpscGrowableArrayQueue<Runnable> writeBuffer = new MpscGrowableArrayQueue<>(WRITE_BUFFER_MIN, WRITE_BUFFER_MAX);

    // 添加写后 Task 到 writeBuffer 中并在合适的时机调度执行任务
    void afterWrite(Runnable task) {
        // 最多重试添加 100 次
        for (int i = 0; i < WRITE_BUFFER_RETRIES; i++) {
            if (writeBuffer.offer(task)) {
                // 写后调度
                scheduleAfterWrite();
                return;
            }
            // 向 writeBuffer 中添加任务失败会调度任务执行
            scheduleDrainBuffers();
            // 自旋等待，让出 CPU 控制权
            Thread.onSpinWait();
        }
        // ...
    }
}
```

`writeBuffer` 的类型为 `MpscGrowableArrayQueue`，在这里我们详细的介绍下它。根据它的命名 **GrowableArrayQueue**
可知它是一个容量可以增长的双端队列，前缀 **MPSC** 表达的含义是“多生产者，单消费者”，也就是说可以有多个线程能向其中添加元素，但只有一个线程能从其中获取元素。那么它是如何实现
**MPSC** 的呢？首先我们先来看一下它的类继承关系图：

`java.util.AbstractQueue` 就不再多解释了。首先我们先来看看命名类似的三个 `BaseMpscLinkedArrayQueuePad`
类，它们三个类实现的内容均一致，所以我们以 `BaseMpscLinkedArrayQueuePad1` 为例来介绍：

```java
abstract class BaseMpscLinkedArrayQueuePad1<E> extends AbstractQueue<E> {
    byte p000, p001, p002, p003, p004, p005, p006, p007;
    byte p008, p009, p010, p011, p012, p013, p014, p015;
    byte p016, p017, p018, p019, p020, p021, p022, p023;
    byte p024, p025, p026, p027, p028, p029, p030, p031;
    byte p032, p033, p034, p035, p036, p037, p038, p039;
    byte p040, p041, p042, p043, p044, p045, p046, p047;
    byte p048, p049, p050, p051, p052, p053, p054, p055;
    byte p056, p057, p058, p059, p060, p061, p062, p063;
    byte p064, p065, p066, p067, p068, p069, p070, p071;
    byte p072, p073, p074, p075, p076, p077, p078, p079;
    byte p080, p081, p082, p083, p084, p085, p086, p087;
    byte p088, p089, p090, p091, p092, p093, p094, p095;
    byte p096, p097, p098, p099, p100, p101, p102, p103;
    byte p104, p105, p106, p107, p108, p109, p110, p111;
    byte p112, p113, p114, p115, p116, p117, p118, p119;
}
```

这个类除了在类内定义了 120 字节的字段外，看上去没有做其他任何事情，实际上它为 **性能提升** 默默做出了贡献，**避免了内存伪共享问题
**。CPU 中缓存行（Cache Line）的大小通常是 64 字节，定义了 120
字节来占位，这样便能将上下继承关系间的字段间隔开，保证被多个线程访问的关键字段距离至少跨越一个缓存行，分布在不同的缓存行中。这样在不同的线程访问 `BaseMpscLinkedArrayQueueProducerFields`
和 `BaseMpscLinkedArrayQueueConsumerFields`
中字段时互不影响，详细了解原理可参考[博客园 - CPU Cache与缓存行](https://www.cnblogs.com/zhongqifeng/p/14765576.html)。

`BaseMpscLinkedArrayQueueProducerFields` 定义生产者相关字段：

```java
abstract class BaseMpscLinkedArrayQueueProducerFields<E> extends BaseMpscLinkedArrayQueuePad1<E> {
    // 生产者当前的索引
    protected long producerIndex;
}
```

`BaseMpscLinkedArrayQueueConsumerFields` 负责定义消费者相关字段：

```java
abstract class BaseMpscLinkedArrayQueueConsumerFields<E> extends BaseMpscLinkedArrayQueuePad2<E> {
    // 掩码值，用于计算消费者实际的索引位置
    protected long consumerMask;
    // 消费者访问这个缓存来获取元素消费
    protected E[] consumerBuffer;
    // 消费者的索引值
    protected long consumerIndex;
}
```

`BaseMpscLinkedArrayQueueColdProducerFields` 中定义字段如下，注意其中命名包含 **Cold**，表示其中字段被访问或修改的比较少：

```java
abstract class BaseMpscLinkedArrayQueueColdProducerFields<E> extends BaseMpscLinkedArrayQueuePad3<E> {
    // 生产者可以生产的最大索引
    protected volatile long producerLimit;
    // 掩码值，用于计算生产者在数组中实际的索引
    protected long producerMask;
    // 存储生产者生产的元素
    protected E[] producerBuffer;
}
```

现在关键字段我们已经介绍完了，接下来看一下创建 `MpscGrowableArrayQueue` 的逻辑，执行它的构造方法时会为我们刚刚提到的字段进行赋值：

```java
class MpscGrowableArrayQueue<E> extends MpscChunkedArrayQueue<E> {

    MpscGrowableArrayQueue(int initialCapacity, int maxCapacity) {
        // 调用父类的构造方法
        super(initialCapacity, maxCapacity);
    }
}

abstract class MpscChunkedArrayQueue<E> extends MpscChunkedArrayQueueColdProducerFields<E> {
    // 省略字节占位字段...
    byte p119;

    MpscChunkedArrayQueue(int initialCapacity, int maxCapacity) {
        // 调用父类的构造方法
        super(initialCapacity, maxCapacity);
    }

}

abstract class MpscChunkedArrayQueueColdProducerFields<E> extends BaseMpscLinkedArrayQueue<E> {
    protected final long maxQueueCapacity;

    MpscChunkedArrayQueueColdProducerFields(int initialCapacity, int maxCapacity) {
        // 调用父类的构造方法
        super(initialCapacity);
        if (maxCapacity < 4) {
            throw new IllegalArgumentException("Max capacity must be 4 or more");
        }
        // 保证了最大值最少比初始值大 2 倍
        if (ceilingPowerOfTwo(initialCapacity) >= ceilingPowerOfTwo(maxCapacity)) {
            throw new IllegalArgumentException(
                    "Initial capacity cannot exceed maximum capacity(both rounded up to a power of 2)");
        }
        // 最大容量也为 2的n次幂
        maxQueueCapacity = ((long) ceilingPowerOfTwo(maxCapacity)) << 1;
    }
}

abstract class BaseMpscLinkedArrayQueue<E> extends BaseMpscLinkedArrayQueueColdProducerFields<E> {

    BaseMpscLinkedArrayQueue(final int initialCapacity) {
        if (initialCapacity < 2) {
            throw new IllegalArgumentException("Initial capacity must be 2 or more");
        }

        // 初始化缓存大小为数值最接近的 2 的 n 次幂
        int p2capacity = ceilingPowerOfTwo(initialCapacity);
        // 掩码值，-1L 使其低位均为 1，左移 1 位则最低位为 0，eg: 00000110，注意该值会被生产者和消费者掩码值共同记录
        long mask = (p2capacity - 1L) << 1;
        // 创建一个大小为 2的n次幂 +1 大小的缓存，注意这个 buffer 分别被 producerBuffer 和 consumerBuffer 共同引用
        E[] buffer = allocate(p2capacity + 1);
        // BaseMpscLinkedArrayQueueColdProducerFields 类中相关字段赋值
        producerBuffer = buffer;
        producerMask = mask;
        // 将 producerLimit 值赋为 掩码值
        soProducerLimit(this, mask);
        // BaseMpscLinkedArrayQueueConsumerFields 类中相关字段赋值
        consumerBuffer = buffer;
        consumerMask = mask;
    }
}
```

向其中添加元素时，会调用 `BaseMpscLinkedArrayQueue#offer` 方法，它是实现 **MPSC** 的核心方法，如下：

```java
abstract class BaseMpscLinkedArrayQueue<E> extends BaseMpscLinkedArrayQueueColdProducerFields<E> {

    private static final Object JUMP = new Object();

    @Override
    @SuppressWarnings("MissingDefault")
    public boolean offer(final E e) {
        if (e == null) {
            throw new NullPointerException();
        }

        long mask;
        E[] buffer;
        long pIndex;

        while (true) {
            // 生产者最大索引（生产者掩码值），获取 BaseMpscLinkedArrayQueueColdProducerFields 中定义的该字段
            long producerLimit = lvProducerLimit();
            // 生产者当前索引，初始值为 0，BaseMpscLinkedArrayQueueProducerFields 中字段 
            pIndex = lvProducerIndex(this);
            // 低位为 1 表示正在扩容，自旋直到扩容完成（表示只有一个线程操作扩容）
            if ((pIndex & 1) == 1) {
                continue;
            }
            // producerIndex 最低位用来表示扩容，右移一位表示实际的索引值

            // 掩码值和buffer可能在扩容中被改变，每次循环使用扩容完成之后 CAS 操作完成的最新值
            mask = this.producerMask;
            buffer = this.producerBuffer;
            // a successful CAS ties the ordering, lv(pIndex)-[mask/buffer]->cas(pIndex)

            // 检查是否需要扩容
            if (producerLimit <= pIndex) {
                int result = offerSlowPath(mask, pIndex, producerLimit);
                switch (result) {
                    case 0:
                        break;
                    case 1:
                        continue;
                    case 2:
                        return false;
                    case 3:
                        resize(mask, buffer, pIndex, e);
                        return true;
                }
            }

            // CAS 操作更新生产者索引，注意这里是 +2，这也正对应了注释中描述的：右移一位表示实际索引
            // 更新成功结束循环
            if (casProducerIndex(this, pIndex, pIndex + 2)) {
                break;
            }
        }
        // 计算该元素在 buffer 中的实际偏移量，并将其封装在 Buffer 中
        final long offset = modifiedCalcElementOffset(pIndex, mask);
        soElement(buffer, offset, e);
        return true;
    }

    // 没有将 resize 逻辑封装在该方法中，而是由该方法判断是否需要扩容，因为不会在扩容时添加元素
    private int offerSlowPath(long mask, long pIndex, long producerLimit) {
        int result;
        // 获取消费者索引 BaseMpscLinkedArrayQueueConsumerFields 类中
        final long cIndex = lvConsumerIndex(this);
        // 通过掩码值计算当前缓冲区容量
        long bufferCapacity = getCurrentBufferCapacity(mask);
        result = 0;// 0 - goto pIndex CAS
        // 如果队列还有空间
        if (cIndex + bufferCapacity > pIndex) {
            // 尝试更新生产者最大限制，更新失败则返回 1 重试
            if (!casProducerLimit(this, producerLimit, cIndex + bufferCapacity)) {
                result = 1;
            }
        }
        // 如果队列已满且无法扩展
        else if (availableInQueue(pIndex, cIndex) <= 0) {
            result = 2;
        }
        // 更新 producerIndex 最低位为 1，成功则进行扩容，否则重试
        else if (casProducerIndex(this, pIndex, pIndex + 1)) {
            result = 3;
        } else {
            result = 1;
        }
        return result;
    }

    private void resize(long oldMask, E[] oldBuffer, long pIndex, final E e) {
        // 计算新缓冲区大小并创建，2 * (buffer.length - 1) + 1
        int newBufferLength = getNextBufferSize(oldBuffer);
        final E[] newBuffer = allocate(newBufferLength);

        // 更新缓冲区引用为新的缓冲区
        producerBuffer = newBuffer;
        // 更新新的掩码
        final int newMask = (newBufferLength - 2) << 1;
        producerMask = newMask;

        // 计算元素在新旧缓冲区中的偏移量
        final long offsetInOld = modifiedCalcElementOffset(pIndex, oldMask);
        final long offsetInNew = modifiedCalcElementOffset(pIndex, newMask);

        // 将元素放到新缓冲区中
        soElement(newBuffer, offsetInNew, e);
        // 将新缓冲区连接到旧缓冲区中
        soElement(oldBuffer, nextArrayOffset(oldMask), newBuffer);

        // 校验可用空间
        final long cIndex = lvConsumerIndex(this);
        final long availableInQueue = availableInQueue(pIndex, cIndex);
        if (availableInQueue <= 0) {
            throw new IllegalStateException();
        }

        // 更新生产者限制大小和生产者索引
        soProducerLimit(this, pIndex + Math.min(newMask, availableInQueue));
        soProducerIndex(this, pIndex + 2);

        // 将旧缓存中该位置的元素更新为 JUMP 标志位，这样在被消费时就知道去新的缓冲区获取了
        soElement(oldBuffer, offsetInOld, JUMP);
    }

    static long modifiedCalcElementOffset(long index, long mask) {
        return (index & mask) >> 1;
    }

    private long nextArrayOffset(final long mask) {
        return modifiedCalcElementOffset(mask + 2, Long.MAX_VALUE);
    }
}
```

可见，在这个过程中它并没有限制操作线程数量，通过使用 **CAS 操作** 和 **可见性** 保证多线程同时添加元素的协同，如下：

```java
abstract class BaseMpscLinkedArrayQueue<E> extends BaseMpscLinkedArrayQueueColdProducerFields<E> {

    static final VarHandle P_INDEX = pIndexLookup.findVarHandle(
            BaseMpscLinkedArrayQueueProducerFields.class, "producerIndex", long.class);
    
    // volatile 可见性保证
    static long lvProducerIndex(BaseMpscLinkedArrayQueue<?> self) {
        return (long) P_INDEX.getVolatile(self);
    }
    
    // CAS 操作
    static boolean casProducerIndex(BaseMpscLinkedArrayQueue<?> self, long expect, long newValue) {
        return P_INDEX.compareAndSet(self, expect, newValue);
    }
}
```

可见性（内存操作对其他线程可见）是通过 **内存屏障** 来保证的，除此之外，内存屏障还能够 **防止重排序**（确保在内存屏障前后的内存操作不会被重排序，从而保证程序的正确性）。接下来我们需要继续看一下 `BaseMpscLinkedArrayQueue#poll` 方法：

```java
abstract class BaseMpscLinkedArrayQueue<E> extends BaseMpscLinkedArrayQueueColdProducerFields<E> {
    
    private static final Object JUMP = new Object();
    
    public E poll() {
        // 读取消费者相关字段 BaseMpscLinkedArrayQueueConsumerFields 类
        final E[] buffer = consumerBuffer;
        final long index = consumerIndex;
        final long mask = consumerMask;

        // 根据消费索引，计算出元素在消费者缓冲区中实际的位置
        final long offset = modifiedCalcElementOffset(index, mask);
        // 读取该元素（volatile 可见性读取）
        Object e = lvElement(buffer, offset);
        
        // 如果为空
        if (e == null) {
            // 比较生产者索引，如果两个索引不相等，那么证明两索引间存在距离表示还有元素能够被消费
            if (index != lvProducerIndex(this)) {
                // 自旋读取元素，直到读到元素
                do {
                    e = lvElement(buffer, offset);
                } while (e == null);
            } else {
                // 索引相等证明确实是空队列
                return null;
            }
        }
        if (e == JUMP) {
            // 获取到新缓冲区
            final E[] nextBuffer = getNextBuffer(buffer, mask);
            // 
            return newBufferPoll(nextBuffer, index);
        }
        // 清除当前索引的元素，表示该元素已经被消费
        soElement(buffer, offset, null);
        // 更新消费者索引，这里也是 +2，所以和生产者索引一样，实际的索引在 modifiedCalcElementOffset 方法中计算完后需要右移 1 位
        soConsumerIndex(this, index + 2);
        return (E) e;
    }

    private E[] getNextBuffer(final E[] buffer, final long mask) {
        // 如果已经发生扩容，此时 consumerMask 仍然对应的是扩容前的 mask
        // 所以此处与生产者操作扩容时拼接新旧缓冲区调用的是一样的方法，这样便能够获取到新缓冲区的偏移量
        final long nextArrayOffset = nextArrayOffset(mask);
        // 获取到新缓冲区
        final E[] nextBuffer = (E[]) lvElement(buffer, nextArrayOffset);
        // 将旧缓冲区中新缓冲区位置设置为 null 表示已经处理过
        soElement(buffer, nextArrayOffset, null);
        return nextBuffer;
    }

    private long nextArrayOffset(final long mask) {
        return modifiedCalcElementOffset(mask + 2, Long.MAX_VALUE);
    }

    private E newBufferPoll(E[] nextBuffer, final long index) {
        // 计算出消费者索引在新缓存中的实际位置
        final long offsetInNew = newBufferAndOffset(nextBuffer, index);
        // 在新缓冲区中获取到对应元素
        final E n = lvElement(nextBuffer, offsetInNew);
        if (n == null) {
            throw new IllegalStateException("new buffer must have at least one element");
        }
        // 清除当前索引的元素，表示该元素已经被消费
        soElement(nextBuffer, offsetInNew, null);
        // 更新消费者索引
        soConsumerIndex(this, index + 2);
        return n;
    }

    private long newBufferAndOffset(E[] nextBuffer, final long index) {
        // 将消费者缓冲区引用和掩码值更新
        consumerBuffer = nextBuffer;
        consumerMask = (nextBuffer.length - 2L) << 1;
        return modifiedCalcElementOffset(index, consumerMask);
    }
    
    static long modifiedCalcElementOffset(long index, long mask) {
        return (index & mask) >> 1;
    }
    
    static <E> E lvElement(E[] buffer, long offset) {
        return (E) REF_ARRAY.getVolatile(buffer, (int) offset);
    }
}
```

可以发现在该方法中并没有做多线程间的同步，所以理论上这个方法可能被多个线程调用，那么它又为什么被称为 **MPSC** 呢？在这个方法的注释中有一段话值得细心体会：

> This implementation is correct for single consumer thread use only.
> 此实现仅适用于单消费者线程使用

所以，猜想调用该方法的一定是一个线程数大小固定为1的线程池，保证单线程调用，至于是不是如此，需要等到在后续的源码中验证了。

到这里 `MpscGrowableArrayQueue` 中核心的逻辑已经讲解完了，现在我们回过头来再看一下队列扩容前后生产者和消费者是如何协同的？在扩容前，`consumerBuffer` 和 `producerBuffer` 引用的是同一个缓冲区对象。如果发生扩容，那么生产者会创建一个新的缓冲区，并将 `producerBuffer` 引用指向它，此时它做了一个 **非常巧妙** 的操作，将 **新缓冲区依然链接到旧缓冲区** 上，并将触发扩容的元素对应的旧缓冲区的索引处标记为 JUMP，表示这及之后的元素已经都在新缓冲区中。此时，消费者依然会在旧缓冲区中慢慢地消费，直到遇到 JUMP 标志位，消费者就知道需要到新缓冲区中取获取元素了。因为之前生产者在扩容时对新旧缓冲区进行链接，所以消费者能够获取到新缓冲区的引用，并变更 `consumerBuffer` 的引用和 `consumerMask` 掩码值，接下来的过程便和扩容前没有差别了。

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

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

在任务处理中状态时，会获取同步锁 `evictionLock` 表示任何时刻只能有一个线程提交任务，提交的任务为 `PerformCleanupTask`
在上文中见到过，它用于执行 `maintenance` 方法，接下来我们看一下具体实现：

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    final ReentrantLock evictionLock;

    // 可重用的任务，用于执行 maintenance 方法，避免了使用 ForkJoinPool 来包装
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
                cache.performCleanUp(null);
            }
        }
        // ...
    }

    // 执行该任务时，也要获取同步锁，表示任务只能由一个线程来执行
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

因为目前关注的是 `put` 方法，所以重点先看 `drainWriteBuffer` 方法处理写缓冲区中的任务：

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    static final int NCPU = Runtime.getRuntime().availableProcessors();

    static final int WRITE_BUFFER_MAX = 128 * ceilingPowerOfTwo(NCPU);

    final MpscGrowableArrayQueue<Runnable> writeBuffer;

    @GuardedBy("evictionLock")
    void drainWriteBuffer() {
        // 最大循环次数为 writeBuffer 最大容量，直至弹出元素为 null
        for (int i = 0; i <= WRITE_BUFFER_MAX; i++) {
            Runnable task = writeBuffer.poll();
            if (task == null) {
                return;
            }
            task.run();
        }
        // 更新状态为 PROCESSING_TO_REQUIRED
        setDrainStatusOpaque(PROCESSING_TO_REQUIRED);
    }
}
```

在上文中我们已经知道 `put` 方法添加的任务为 `AddTask`，下面我们看一下该任务的实现：

```java
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef implements LocalCache<K, V> {

    static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;

    final class AddTask implements Runnable {
        final Node<K, V> node;
        // 节点权重
        final int weight;

        AddTask(Node<K, V> node, int weight) {
            this.weight = weight;
            this.node = node;
        }

        @Override
        @GuardedBy("evictionLock")
        @SuppressWarnings("FutureReturnValueIgnored")
        public void run() {
            // 是否指定了驱逐策略
            if (evicts()) {
                // 更新缓存权重大小和窗口权重
                setWeightedSize(weightedSize() + weight);
                setWindowWeightedSize(windowWeightedSize() + weight);
                // 更新节点 policyWeight
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
                    // 如果权重比配置的最大权重大
                    if (weight > maximum()) {
                        // 执行驱逐策略
                        evictEntry(node, RemovalCause.SIZE, expirationTicker().read());
                    }
                    // 如果权重超过窗口区最大权重，则将其放在窗口区头节点
                    else if (weight > windowMaximum()) {
                        accessOrderWindowDeque().offerFirst(node);
                    }
                    // 否则放在窗口区尾节点
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

todo evictEntry

`put` 向缓存中添加不存在的元素时，

元素被添加到 `ConcurrentHashMap data` 和窗口区（`WindowDeque`）中，根据节点权重大小区分是

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

### 巨人的肩膀

- [博客园 - CPU Cache与缓存行](https://www.cnblogs.com/zhongqifeng/p/14765576.html)