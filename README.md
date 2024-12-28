## 网海流量凶，缓存知多少

### 热 key 问题如何解决

某个或某些特定的缓存键被频繁访问，导致这些键的访问量远超其他键。这种情况可能会导致缓存服务器的负载不均衡，某个节点负载很高，其他节点十分空闲，占用大量 CPU 使其性能变差进而影响其他请求，甚至造成访问压力过大超出缓存的承载能在造成缓存击穿，导致大量请求直接指向后端数据库，造成数据库宕机，影响业务。

解决该问题的核心是 **“缓存分片”**，如果使用的是分布式缓存，可以为分布式缓存添加多个节点，这样便**将热 key 分散到多个缓存节点上**，通过轮询或其他负载均衡算法将流量打散，从而避免单个缓存节点压力过大。另一种解决方案是在应用服务器上使用本地缓存，本质上也是为缓存做了分片，将访问的流量打散，分布到各个应用服务器上，降低单台服务器的压力。

### 大 key 问题如何解决


### 缓存穿透


### 缓存击穿


### 缓存雪崩



---

### 巨人的肩膀

- [CoolShell - 缓存更新的套路](https://coolshell.cn/articles/17416.html)
- [Java全栈知识体系 - JUC集合](https://pdai.tech/md/java/thread/java-thread-x-juc-collection-ConcurrentHashMap.html#concurrenthashmap-jdk-1-8)
- [Github - Caffeine](https://github.com/ben-manes/caffeine)
- [High Scalability - Design of a Modern Cache](https://highscalability.com/design-of-a-modern-cache/)
- [现代化的缓存设计方案](http://ifeve.com/design-of-a-modern-cache/)
- [High Scalability - Cache Eviction and Expiration Policy](https://highscalability.com/design-of-a-modern-cachepart-deux/)
