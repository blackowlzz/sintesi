package dev.sintesi.util;

import dev.sintesi.net.SubnetKey;

public final class LongLongMap {

    public interface Entry {
        void accept(long key, long value);
    }

    public interface Keep {
        boolean test(long key, long value);
    }

    private static final int MIN_CAPACITY = 16;

    private long[] keys;
    private long[] values;
    private boolean[] used;
    private int size;

    public LongLongMap() {
        allocate(MIN_CAPACITY);
    }

    public LongLongMap(LongLongMap other) {
        keys = other.keys.clone();
        values = other.values.clone();
        used = other.used.clone();
        size = other.size;
    }

    public int size() {
        return size;
    }

    public long get(long key) {
        int mask = keys.length - 1;
        int index = (int) SubnetKey.mix(key) & mask;
        while (used[index]) {
            if (keys[index] == key) {
                return values[index];
            }
            index = (index + 1) & mask;
        }
        return 0L;
    }

    public void put(long key, long value) {
        if ((size + 1) * 4 > keys.length * 3) {
            resize(keys.length * 2);
        }
        int mask = keys.length - 1;
        int index = (int) SubnetKey.mix(key) & mask;
        while (used[index]) {
            if (keys[index] == key) {
                values[index] = value;
                return;
            }
            index = (index + 1) & mask;
        }
        used[index] = true;
        keys[index] = key;
        values[index] = value;
        size++;
    }

    public void forEach(Entry consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (used[i]) {
                consumer.accept(keys[i], values[i]);
            }
        }
    }

    public int retain(Keep keep) {
        long[] oldKeys = keys;
        long[] oldValues = values;
        boolean[] oldUsed = used;
        int before = size;
        allocate(keys.length);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i] && keep.test(oldKeys[i], oldValues[i])) {
                put(oldKeys[i], oldValues[i]);
            }
        }
        return before - size;
    }

    public void remove(long key) {
        retain((candidate, value) -> candidate != key);
    }

    private void resize(int capacity) {
        long[] oldKeys = keys;
        long[] oldValues = values;
        boolean[] oldUsed = used;
        allocate(capacity);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new long[capacity];
        used = new boolean[capacity];
        size = 0;
    }
}
