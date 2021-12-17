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

    public void setLog(
            LinkedHashMap<Integer, ByteBuf> log) {
        this.log = log;
    }



}




