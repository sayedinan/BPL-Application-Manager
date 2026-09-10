package com.bpl.orderapp.admin.log;
import java.util.*;
import java.util.concurrent.*;
public class LogPollerScheduler {
    private final Map<Long, ScheduledExecutorService> pollers = new HashMap<>();
    // §7.1: one per app, only while RUNNING, interval from app.poll_interval_seconds, cancel on STOPPED/ERROR
    public void startPoller(Long appId, int intervalSec, Runnable tick) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor();
        s.scheduleAtFixedRate(tick, 0, intervalSec, TimeUnit.SECONDS); // immediate on RUNNING
        pollers.put(appId, s);
    }
    public void stopPoller(Long appId) {
        ScheduledExecutorService s = pollers.remove(appId);
        if (s != null) s.shutdownNow();
    }
}
