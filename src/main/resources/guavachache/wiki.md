[Guava Cache Wiki](https://github.com/google/guava/wiki/CachesExplained)

### 直接插入缓存

可通过 `cache.put(key, value)` **直接插入值**，这会覆盖指定键的所有先前条目。也可通过 `Cache.asMap()` 返回的 `ConcurrentMap` 视图操作缓存。但需注意：

1. **`asMap` 视图的限制**：
    - **不会自动加载缓存条目**，所有操作需手动处理。
    - 其原子操作（如 `putIfAbsent`）**不参与自动缓存加载逻辑**，因此在需要自动加载的场景中，**优先使用 `Cache.get(K, Callable)`** 而非 `Cache.asMap().putIfAbsent()`。
    - 即使通过 `Callable` 或 `CacheLoader` 加载值，`Cache.get(K, Callable)` 也可能向底层缓存插入值。

### 总结
- **自动加载优先**：通过 `CacheLoader` 或 `Callable` 实现自动加载，确保一致性和原子性。
- **异常处理**：根据场景选择 `get(K)` 或 `getUnchecked(K)`，正确处理 `ExecutionException`。
- **批量优化**：通过覆写 `loadAll` 提升批量查询效率。
- **谨慎直接操作**：直接插入或通过 `asMap` 操作需手动管理一致性，非必要不优先使用。