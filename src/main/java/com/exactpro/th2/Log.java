/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2;

import io.netty.buffer.ByteBuf;

import java.util.LinkedHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Log {
    private LinkedHashMap<Integer, ByteBuf> log;
    private final int maxSize;
    private ReadWriteLock readWriteLock;

    public Log(int maxSize) {
        this.log = new LinkedHashMap<>();
        this.maxSize = maxSize;
        readWriteLock = new ReentrantReadWriteLock();
    }

    public void put(Integer tag, ByteBuf message) {
        if (log.size() >= maxSize && maxSize > 0) {
            int oldTag = log.keySet().iterator().next();
            remove(oldTag);
        }

        log.put(tag, message);
    }

    public ByteBuf get(Integer tag) {
        Lock readLock = readWriteLock.readLock();
        try {
            readLock.lock();
            return log.get(tag);
        } finally {
            readLock.unlock();
        }
    }

    public boolean containsKey(Integer tag) {
        Lock readLock = readWriteLock.readLock();
        try {
            readLock.lock();
            return log.containsKey(tag);
        } finally {
            readLock.unlock();
        }
    }

    private void remove(Integer tag) {
        Lock writeLock = readWriteLock.writeLock();
        try {
            writeLock.lock();
            log.remove(tag);
        } finally {
            writeLock.unlock();
        }
    }

    public LinkedHashMap<Integer, ByteBuf> getLog() {
        return log;
    }
}




