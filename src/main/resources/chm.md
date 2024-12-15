

### 构造方法

```java
public class ConcurrentHashMap<K,V> extends AbstractMap<K,V> implements ConcurrentMap<K,V>, Serializable {
    private transient volatile int sizeCtl;

    private static final int MAXIMUM_CAPACITY = 1 << 30;

    /**
     * 构造方法，其他构造函数最终都会调用该方法，但实际上在构造方法中并没有完成初始化
     * 
     * @param initialCapacity  指定初始化大小
     * @param loadFactor       负载因子，注意该值并没有被任何字段记录下来，而是只参与了 size 的计算
     * @param concurrencyLevel 指定并发线程数，用于校正大小
     */
    public ConcurrentHashMap(int initialCapacity, float loadFactor, int concurrencyLevel) {
        if (!(loadFactor > 0.0f) || initialCapacity < 0 || concurrencyLevel <= 0)
            throw new IllegalArgumentException();
        // 如果指定的并发线程数大于初始化容量，那么以并发线程数为准
        if (initialCapacity < concurrencyLevel)
            initialCapacity = concurrencyLevel;
        long size = (long) (1.0 + (long) initialCapacity / loadFactor);
        int cap = (size >= (long) MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : tableSizeFor((int) size);
        this.sizeCtl = cap;
    }

    // 向上取整 2的n次幂
    private static final int tableSizeFor(int c) {
        // Integer.numberOfLeadingZeros(c - 1) 用于计算 c-1 的二进制表示中最高位 1 之前有多少个 0
        // -1 的二级制表示为 11111111111111111111111111111111（32个1），无符号右移则会得到某 2的n次幂-1 的结果
        int n = -1 >>> Integer.numberOfLeadingZeros(c - 1);
        // 限制最大值的同时，结果永远为 2的n次幂
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }
    
}
```

`loadFactor` 负载因子通常被指定为 **0.75F**，并且在源码中也提供了该默认值，如此的原因在源码的 JavaDoc 中有详细的介绍：

> 理想情况下，容器中的节点遵循泊松分布，一个桶中有 k 个元素的概率分布如下：
>
> 0：0.60653066
> 
> 1：0.30326533
>
> 2：0.07581633
>
> 3：0.01263606
>
> 4：0.00157952
>
> 5：0.00015795
>
> 6：0.00001316
>
> 7：0.00000094
>
> 8：0.00000006
>
> 更多：不到千万分之一
>
> 在随机散列下，两个线程访问不同元素的锁争用概率约为 1 / (8 * 元素数量)。

该阈值能够较好的防止多个元素发生碰撞，在随机散列的情况下，多线程发生锁争抢的概率较低。负载因子 `loadFactor` 作为局部变量计算完 size 后，并没有被记录下来，后续有关该值的逻辑，如扩容阈值的计算均使用了默认值 0.75F。 

### put 方法

```java
public class ConcurrentHashMap<K,V> extends AbstractMap<K,V> implements ConcurrentMap<K,V>, Serializable {

    static final int TREEIFY_THRESHOLD = 8;

    // 最高位为 0，其余位为 1
    static final int HASH_BITS = 0x7fffffff;

    private static final int DEFAULT_CAPACITY = 16;
    
    transient volatile Node<K,V>[] table;

    private transient volatile int sizeCtl;
    
    public V put(K key, V value) {
        return putVal(key, value, false);
    }
    
    final V putVal(K key, V value, boolean onlyIfAbsent) {
        // key 和 value 均不能为 null
        if (key == null || value == null) throw new NullPointerException();
        // hash 扰动，用于使 hash 结果均匀分布
        int hash = spread(key.hashCode());
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh; K fk; V fv;
            // 懒加载实现初始化
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            // 如果元素 hash （数组大小 - 1 & hash）到桶的位置没有元素（为 null）
            else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
                // 通过 CAS 操作直接将元素放到该位置
                if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value)))
                    break;
            }
            // 扩容相关逻辑，后续再讲解
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            // onlyIfAbsent 是入参，默认为 false，表示键不存在时才插入新值，否则相同键值不能覆盖
            // 该逻辑满足在此特定条件下，避免获取锁，从而提高性能
            else if (onlyIfAbsent
                    && fh == hash
                    && ((fk = f.key) == key || (fk != null && key.equals(fk)))
                    && (fv = f.val) != null)
                return fv;
            else { // 执行到此处意味着该元素 hash 到的桶位置存在元素，需要追加到此处的链表或红黑树上，f 为该桶位置的第一个元素
                V oldVal = null;
                // 锁住第一个元素
                synchronized (f) {
                    // 校验此处元素并没有发生变更（类似单例模式的双重检测锁机制）
                    if (tabAt(tab, i) == f) {
                        // 头节点的 hash 值大于 0，说明是链表
                        if (fh >= 0) {
                            binCount = 1;
                            // 注意这里会对链表中元素数量进行累加
                            for (Node<K,V> e = f;; ++binCount) {
                                K ek;
                                // 如果发现了 key 值相同的元素，根据 onlyIfAbsent 字段判断是否需要覆盖
                                if (e.hash == hash &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    oldVal = e.val;
                                    if (!onlyIfAbsent)
                                        e.val = value;
                                    break;
                                }
                                // 直到找到链表的尾巴节点，进行添加（尾插法）
                                Node<K,V> pred = e;
                                if ((e = e.next) == null) {
                                    pred.next = new Node<K,V>(hash, key, value);
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) { // 如果该元素是红黑树
                            Node<K,V> p;
                            binCount = 2;
                            // 调用红黑树添加元素的方法
                            if ((p = ((TreeBin<K,V>)f).putTreeVal(hash, key,
                                    value)) != null) {
                                oldVal = p.val;
                                if (!onlyIfAbsent)
                                    p.val = value;
                            }
                        }
                        // ReservationNode 为 computeIfAbsent 和 compute 方法中使用的占位节点，同样也是为了保证并发环境下的正确性和一致性
                        else if (f instanceof ReservationNode)
                            throw new IllegalStateException("Recursive update");
                    }
                }
                if (binCount != 0) {
                    // 如果链表中元素数量超过了树化阈值，则将链表转换为红黑树
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    if (oldVal != null)
                        return oldVal;
                    break;
                }
            }
        }
        // todo
        addCount(1L, binCount);
        return null;
    }

    // 扰动函数，用于计算哈希值以减少碰撞的可能性
    static final int spread(int h) {
        // 右移 16 位后，使该 hash 值的高 16 位和低 16 位“混合”，使 hash 值均匀分布，位与操作则是限制哈希值的范围，并保证它为非负数
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    private final Node<K,V>[] initTable() {
        Node<K,V>[] tab; int sc;
        while ((tab = table) == null || tab.length == 0) {
            if ((sc = sizeCtl) < 0)
                Thread.yield();
            // 通过 CAS 操作将 sizeCtl 赋值为 -1，成功后 sizeCtl 为 -1 表示正在有线程在对它进行初始化
            // 如果此时再有其他线程来操作，CAS 操作会失败，会在 while 循环中自旋等待直到完成初始化
            else if (U.compareAndSetInt(this, SIZECTL, sc, -1)) {
                try {
                    if ((tab = table) == null || tab.length == 0) {
                        int n = (sc > 0) ? sc : DEFAULT_CAPACITY;
                        @SuppressWarnings("unchecked")
                        Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                        table = tab = nt;
                        // 计算扩容阈值，相当于原值的 0.75F，构造函数中指定的负载因子不会生效，均采用默认 0.75F 来计算
                        sc = n - (n >>> 2);
                    }
                } finally {
                    sizeCtl = sc;
                }
                break;
            }
        }
        return tab;
    }
}
```

在这段源码逻辑中，我能能发现一些具有“隐藏”性的赋值操作，比如在如下逻辑中，变量 `n` 的赋值：

```java
public class ConcurrentHashMap<K,V> extends AbstractMap<K,V> implements ConcurrentMap<K,V>, Serializable {
    // ...
    
    final V putVal(K key, V value, boolean onlyIfAbsent) {
        // ...
        
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh; K fk; V fv;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            // ...
        }
    }
    
}
```

它是在 **if 条件** 判断中完成赋值的，这样写代码确实相对精简一些，但是也仅限于此（或非业务技术组件中），我觉得如果在业务代码中这样写，可读性就比较差了。


key 和 value 不能为 null 的妙用

只锁定头节点的弊端
