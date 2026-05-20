package com.mta.faultinjection.util;

/**
 * Tiny seam around {@link Thread#sleep(long)} so the synchronous interceptor
 * can be exercised in unit tests without actually blocking a thread.
 */
@FunctionalInterface
public interface Sleeper {

    /** Default implementation that really blocks the current thread. */
    Sleeper DEFAULT = Thread::sleep;

    void sleep(long millis) throws InterruptedException;
}
