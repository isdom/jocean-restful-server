package org.jocean.restful;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.Function;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.StopWatch;
import org.jocean.j2se.stats.MultilevelStats;
import org.jocean.j2se.stats.TIMemos.EmitableTIMemo;

import rx.functions.Action1;

public class FlowStats {
    
    public int incExecutedCount(final Class<?> cls) {
        return this._executedCounters.get(cls).incrementAndGet();
    }
    
    public int getExecutedCount(final Class<?> cls) {
        return this._executedCounters.get(cls).get();
    }
    
    public void recordExecutedInterval(final Class<?> cls, final String endreason, final StopWatch clock) {
        this._executedTIMemos.recordInterval(clock.stopAndRestart(), cls, endreason);
    }
    
    public void fetchExecutedInterval(final Class<?> cls, final Action1<String> receptor) {
        final Map<String, EmitableTIMemo> snapshot = this._executedTIMemos.fetchStatsSnapshot(cls);
        int idx = 1;
        for (Map.Entry<String, EmitableTIMemo> entry : snapshot.entrySet()) {
            receptor.call( "(" + Integer.toString(idx++) + ")." + entry.getKey() + ":");
            entry.getValue().emit(receptor);
        }
    }

    private final SimpleCache<Class<?>, AtomicInteger> _executedCounters = new SimpleCache<>(
            new Function<Class<?>, AtomicInteger>() {
        @Override
        public AtomicInteger apply(final Class<?> input) {
            return new AtomicInteger(0);
        }});
    
    private final MultilevelStats _executedTIMemos = MultilevelStats.Util.buildStats(2);
}
