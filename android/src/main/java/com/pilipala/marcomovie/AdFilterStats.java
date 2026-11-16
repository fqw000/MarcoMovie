package com.pilipala.marcomovie;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe counter for ad-filter hits within one playback session. {@link AdFilterDataSource}
 * records removals from its media-playlist requests; the playback UI observes the counter to show
 * a transient "filtered N segments" notice.
 */
final class AdFilterStats {
    interface Listener {
        void onFiltered(int totalRemoved, int justRemoved);
    }

    private final AtomicInteger total = new AtomicInteger();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    void record(int count) {
        if (count <= 0) return;
        int totalRemoved = total.addAndGet(count);
        for (Listener listener : listeners) {
            try {
                listener.onFiltered(totalRemoved, count);
            } catch (RuntimeException ignored) {
            }
        }
    }

    int total() {
        return total.get();
    }

    void reset() {
        total.set(0);
    }

    void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
