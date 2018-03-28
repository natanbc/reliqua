package com.github.natanbc.reliqua.limiter;

import javax.annotation.Nonnull;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings({"unused", "WeakerAccess"})
public class DefaultRateLimiter extends RateLimiter {
    protected final Queue<Runnable> pendingRequests = new ConcurrentLinkedQueue<>();
    protected final AtomicInteger requestsDone = new AtomicInteger(0);
    protected final AtomicLong ratelimitResetTime = new AtomicLong();
    protected final DefaultRateLimiter parent;
    protected final ScheduledExecutorService executor;
    protected final Callback callback;
    protected final int maxRequests;
    protected final long cooldownMillis;
    protected Future<?> ratelimitTimeResetFuture;

    private DefaultRateLimiter(DefaultRateLimiter parent, ScheduledExecutorService executor, Callback callback, int maxRequests, long cooldownMillis) {
        this.parent = parent;
        this.executor = executor;
        this.callback = callback;
        this.maxRequests = maxRequests;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * Creates a new rate limiter.
     *
     * @param executor The executor to schedule cooldowns and rate limit processing.
     * @param callback Callback to be notified about rate limiter actions.
     * @param maxRequests Maximum amount of requests that may be done before being rate limited.
     * @param cooldownMillis Time to reset the requests done, starting from the first request done since the last reset.
     */
    public DefaultRateLimiter(ScheduledExecutorService executor, Callback callback, int maxRequests, long cooldownMillis) {
        this(null, executor, callback, maxRequests, cooldownMillis);
    }

    @Override
    public void queue(@Nonnull Runnable task) {
        pendingRequests.offer(task);
        executor.execute(this::process);
    }

    @Override
    public int getRemainingRequests() {
        return maxRequests - requestsDone.get();
    }

    @Override
    public long getTimeUntilReset() {
        return TimeUnit.NANOSECONDS.toMillis(rateLimitResetNanos());
    }

    @Nonnull
    @Override
    public RateLimiter createChildLimiter(int requests, long cooldown) {
        return super.createChildLimiter(requests, cooldown);
    }

    protected boolean isOverQuota() {
        return (parent != null && parent.isOverQuota()) || requestsDone.get() >= maxRequests;
    }

    protected boolean canExecuteRequest() {
        return (parent == null || parent.canExecuteRequest()) && requestsDone.incrementAndGet() < maxRequests;
    }

    protected long rateLimitResetNanos() {
        return Math.min(ratelimitResetTime.get() - System.nanoTime(), parent == null ? Long.MAX_VALUE : parent.rateLimitResetNanos());
    }

    protected void process() {
        Runnable r = pendingRequests.peek();
        if(r == null) return;
        if(isOverQuota()) {
            executor.schedule(this::process, rateLimitResetNanos(), TimeUnit.NANOSECONDS);
            if(callback != null) {
                try {
                    callback.requestRateLimited();
                } catch(Exception ignored) {}
            }
            return;
        }
        if(!canExecuteRequest()) {
            synchronized(this) {
                if(ratelimitTimeResetFuture != null) {
                    executor.schedule(this::process, rateLimitResetNanos(), TimeUnit.NANOSECONDS);
                    if(callback != null) {
                        try {
                            callback.requestRateLimited();
                        } catch(Exception ignored) {}
                    }
                    return;
                }
                ratelimitTimeResetFuture = executor.schedule(()->{
                    ratelimitResetTime.set(0);
                    requestsDone.set(0);
                    ratelimitTimeResetFuture = null;
                    if(callback != null) {
                        try {
                            callback.rateLimitReset();
                        } catch(Exception ignored) {}
                    }
                }, cooldownMillis, TimeUnit.MILLISECONDS);
                executor.schedule(this::process, cooldownMillis, TimeUnit.MILLISECONDS);
                return;
            }
        }
        pendingRequests.poll().run();
        ratelimitResetTime.compareAndSet(0, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cooldownMillis));
    }
}
