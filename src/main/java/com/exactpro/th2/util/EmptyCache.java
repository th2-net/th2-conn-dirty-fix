/*
 * Copyright 2022-2024 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

public final class EmptyCache<K, V> implements Cache<K, V> {
    @SuppressWarnings("rawtypes")
    private static final Cache EMPTY_CACHE = new EmptyCache<>();
    private static final CacheStats EMPTY_CACHE_STATS = new CacheStats(0, 0, 0, 0, 0, 0);

    private EmptyCache() { }

    @SuppressWarnings("unchecked")
    public static <K,V> Cache<K,V> emptyCache() {
        return (Cache<K,V>) EMPTY_CACHE;
    }

    @Nullable
    @Override
    public V getIfPresent(@NotNull Object key) {
        return null;
    }

    @Override
    public @NotNull V get(@NotNull K key, @NotNull Callable<? extends V> loader) throws ExecutionException {
        try {
            return loader.call();
        } catch (Exception e) {
            throw new ExecutionException(e);
        }
    }

    @Override
    public @NotNull ImmutableMap<K, V> getAllPresent(@NotNull Iterable<?> keys) {
        return ImmutableMap.of();
    }

    @Override
    public void put(@NotNull K key, @NotNull V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(@NotNull Map<? extends K, ? extends V> m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void invalidate(@NotNull Object key) { }

    @Override
    public void invalidateAll(@NotNull Iterable<?> keys) { }

    @Override
    public void invalidateAll() { }

    @Override
    public long size() {
        return 0;
    }

    @Override
    public @NotNull CacheStats stats() {
        return EMPTY_CACHE_STATS;
    }

    @Override
    public @NotNull ConcurrentMap<K, V> asMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void cleanUp() { }
}
