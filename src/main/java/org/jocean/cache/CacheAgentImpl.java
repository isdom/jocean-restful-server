/**
 * 
 */
package org.jocean.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

import org.jocean.event.api.AbstractUnhandleAware;
import org.jocean.event.api.EventEngine;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.FetchAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class CacheAgentImpl<KEY, CTX, VALUE> implements FetchAgent<KEY, CTX, VALUE> {

    private static final Logger LOG = 
            LoggerFactory.getLogger(CacheAgentImpl.class);

    public CacheAgentImpl(final FetchAgent<KEY, CTX, VALUE> fetchAgent, final Cache cache, 
            final EventEngine engine) {
        this._agent = fetchAgent;
        this._cache = cache;
        this._engine = engine;
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public Detachable fetchAsync(
            final KEY key, final CTX ctx, final FetchReactor<CTX, VALUE> reactor) {
        final Element  element = this._cache.get(key);
        if( null != element ){
            if ( LOG.isTraceEnabled()) {
                LOG.trace("fetchAsync: found element({}) for ctx({})/key({}), return just now",
                        element, ctx, key);
            }
            try {
                reactor.onFetchComplete(ctx, (VALUE)element.getObjectValue());
            }
            catch (Throwable e) {
                LOG.warn("exception when FetchReactor.onFetchResult for ctx({})/key({}), detail:{}",
                        ctx, key, ExceptionUtils.exception2detail(e));
            }
            return Detachable.DoNothing;
        }
        else {
            if ( LOG.isTraceEnabled()) {
                LOG.trace("fetchAsync: CAN'T found element for ctx({})/key({}), star async fetching",
                        ctx, key);
            }
            final int idx = this._idx.getAndIncrement();
            final FetchAndCacheFlow<KEY, CTX, VALUE> flow = safeGetFlowFor(key);
            final AtomicBoolean canceled = new AtomicBoolean(false);
            try {
                flow.selfEventReceiver().acceptEvent(new AbstractUnhandleAware("addReactor") {
                    @Override
                    public void onEventUnhandle(final String event, final Object... args)
                            throws Exception {
                        //  TODO, now on reftch result with null value
                        try {
                            reactor.onFetchComplete(ctx, null);
                        }
                        catch (Throwable e) {
                            LOG.warn("exception when FetchReactor.onFetchResult for ctx({})/key({}), detail:{}",
                                    ctx, key, ExceptionUtils.exception2detail(e));
                        }
                    }}, idx, ctx, new FetchReactor<CTX, VALUE>() {
                        @Override
                        public void onFetchComplete(final CTX _ctx, final VALUE value)
                                throws Exception {
                            if (!canceled.get()) {
                                reactor.onFetchComplete(_ctx, value);
                            }
                            else {
                                if (LOG.isTraceEnabled()) {
                                    LOG.trace("ctx({})/key({})'s reactor has been canceled", _ctx, key);
                                }
                            }
                        }});
            }
            catch (Throwable e) {
                LOG.warn("exception when acceptEvent for addFetch, detail:{}",
                        ExceptionUtils.exception2detail(e));
            }
            return new Detachable() {
                @Override
                public void detach() throws Exception {
                    canceled.set(true);
                    flow.selfEventReceiver().acceptEvent("removeReactor", idx);
                }};
        }
    }
    
    private FetchAndCacheFlow<KEY, CTX, VALUE> safeGetFlowFor(final KEY key) {
        FetchAndCacheFlow<KEY, CTX, VALUE> flow = this._flows.get(key);
        if ( null != flow ) {
            return flow;
        }
        flow = new FetchAndCacheFlow<KEY, CTX, VALUE>(this._cache, this._agent, key);
        final FetchAndCacheFlow<KEY, CTX, VALUE> previousFlow = this._flows.putIfAbsent(key, flow);
        if ( null != previousFlow ) {
            return previousFlow;
        }
        flow.addFlowLifecycleListener(new FlowLifecycleListener() {
            @Override
            public void afterEventReceiverCreated(
                    final EventReceiver receiver) throws Exception {
            }
            @Override
            public void afterFlowDestroy()
                    throws Exception {
                _flows.remove(key);
            }});
        this._engine.create(flow.toString(), flow.WAIT, flow);
        return flow;
    }

    private EventEngine _engine;
    private final FetchAgent<KEY, CTX, VALUE> _agent;
    private final Cache _cache;
    private final ConcurrentMap<KEY, FetchAndCacheFlow<KEY, CTX, VALUE>> _flows = 
            new ConcurrentHashMap<KEY, FetchAndCacheFlow<KEY, CTX, VALUE>>();
    private final AtomicInteger _idx = new AtomicInteger(0);
}
