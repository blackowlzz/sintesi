package dev.sintesi.net;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class LoginQueue {

    public record Waiting(long since, BooleanSupplier alive, BooleanSupplier admit, Runnable admitted,
            Runnable expired) {
    }

    private final ConcurrentLinkedQueue<Waiting> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final LongSupplier clock;
    private volatile int capacity;
    private volatile long maxWaitMillis;

    public LoginQueue(int capacity, long maxWaitMillis, LongSupplier clock) {
        this.capacity = capacity;
        this.maxWaitMillis = maxWaitMillis;
        this.clock = clock;
    }

    public void settings(int capacity, long maxWaitMillis) {
        this.capacity = capacity;
        this.maxWaitMillis = maxWaitMillis;
    }

    public boolean isEmpty() {
        return size.get() == 0;
    }

    public int size() {
        return size.get();
    }

    public boolean offer(BooleanSupplier alive, BooleanSupplier admit, Runnable admitted, Runnable expired) {
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            return false;
        }
        queue.add(new Waiting(clock.getAsLong(), alive, admit, admitted, expired));
        return true;
    }

    public synchronized void process() {
        long now = clock.getAsLong();
        Waiting head;
        while ((head = queue.peek()) != null) {
            if (!head.alive().getAsBoolean()) {
                drop(head);
                continue;
            }
            if (now - head.since() > maxWaitMillis) {
                drop(head);
                head.expired().run();
                continue;
            }
            if (!head.admit().getAsBoolean()) {
                return;
            }
            drop(head);
            head.admitted().run();
        }
    }

    private void drop(Waiting waiting) {
        if (queue.remove(waiting)) {
            size.decrementAndGet();
        }
    }
}
