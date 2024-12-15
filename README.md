### 网海流量凶，缓存知多少

### ConcurrentHashMap

`ConcurrentHashMap` 提供了与 `HashTable` 相同的方法，所以它能够在程序中无缝替换 `Hashtable`，它们都是线程安全的，不会抛出 `ConcurrentModificationException`。在创建 `ConcurrentHashMap` 时，最好评估出需要的缓存容量，这样能够避免在使用中发生扩容操作，否则该操作比较耗时。与 `HashMap` 不同的是，它不支持 key 或 value 为 null，

#### 不支持为 null 的妙用

与大多数 `Stream` 操作不同，它的 `forEach`, `search` 和 `reduce` 方法在其他线程并发更新时也能安全地执行，可以通过入参 `parallelismThreshold` 来调节并发阈值。


#### 加锁

在空桶中插入第一个节点（通过 put 或其变体）是通过将其 CAS 到桶中来完成的。这是大多数 put 操作的最常见情况，适用于大多数键/哈希分布。其他更新操作（插入、删除和替换）需要锁。我们不想浪费空间来为每个桶关联一个不同的锁对象，因此使用桶列表的第一个节点本身作为锁。这些锁的锁定支持依赖于内置的“同步”监视器。

使用列表的第一个节点作为锁本身并不足够：当一个节点被锁定时，任何更新都必须首先验证在锁定后它仍然是第一个节点，并在不是时重试。因为新节点总是附加到列表中，一旦一个节点在一个桶中是第一个，它将保持第一个，直到被删除或桶失效（在调整大小时）。

每桶锁的主要缺点是，受到同一锁保护的桶列表中的其他节点上的更新操作可能会被阻塞，例如当用户的 equals() 或映射函数耗时较长时。然而，统计上，在随机哈希码下，这不是一个常见问题。理想情况下，桶中节点的频率遵循参数约为 0.5 的泊松分布，给定调整大小阈值为 0.75，尽管由于调整大小粒度的原因存在较大方差。忽略方差，列表大小 k 的预期出现次数为（exp(-0.5) * pow(0.5, k) / factorial(k)）。前几个值是：


---

### 巨人的肩膀

- [CoolShell - 缓存更新的套路](https://coolshell.cn/articles/17416.html)
- [Java全栈知识体系 - JUC集合](https://pdai.tech/md/java/thread/java-thread-x-juc-collection-ConcurrentHashMap.html#concurrenthashmap-jdk-1-8)
- [Github - Caffeine](https://github.com/ben-manes/caffeine)
- [High Scalability - Design of a Modern Cache](https://highscalability.com/design-of-a-modern-cache/)
- [现代化的缓存设计方案](http://ifeve.com/design-of-a-modern-cache/)
- [High Scalability - Cache Eviction and Expiration Policy](https://highscalability.com/design-of-a-modern-cachepart-deux/)
