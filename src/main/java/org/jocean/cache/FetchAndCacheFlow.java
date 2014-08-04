/**
 * 
 */
package org.jocean.cache;

import java.util.HashMap;
import java.util.Map;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.FetchAgent;
import org.jocean.idiom.Pair;
import org.jocean.idiom.FetchAgent.FetchReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
class FetchAndCacheFlow<KEY, CTX, VALUE> extends AbstractFlow<FetchAndCacheFlow<KEY, CTX, VALUE>> {

	private static final Logger LOG = LoggerFactory
			.getLogger(FetchAndCacheFlow.class);

    public FetchAndCacheFlow(final Cache cache, final FetchAgent<KEY, CTX, VALUE> agent, final KEY key) {
        this._cache = cache;
        this._fetchAgent = agent;
        this._key = key;
    }
    
    final BizStep WAIT = new BizStep("fetchAndCache.WAIT")
            .handler(selfInvoker("onStartFetch"))
			.freeze();
	
	private final BizStep FETCHING = new BizStep("fetchAndCache.FETCHING")
            .handler(selfInvoker("onAddReactor"))
            .handler(selfInvoker("onRemoveReactor"))
            .handler(selfInvoker("onFetchComplete"))
			.freeze();

	@SuppressWarnings("unchecked")
    @OnEvent(event="addReactor")
	private BizStep onStartFetch(final int idx, final CTX ctx, final FetchReactor<CTX, VALUE> reactor) 
	        throws Exception {
		if ( LOG.isTraceEnabled() ) {
			LOG.trace("start fetch for ctx({})/key({})", ctx, this._key);
		}
		this._reactors.put(idx, Pair.of(ctx, reactor));
		this._fetchAgent.fetchAsync(this._key, ctx, 
		        (FetchReactor<CTX, VALUE>)this.queryInterfaceInstance(FetchReactor.class));
		return FETCHING;
	}

    @OnEvent(event="addReactor")
    private BizStep onAddReactor(final int idx, final CTX ctx, final FetchReactor<CTX, VALUE> reactor) 
            throws Exception {
        if ( LOG.isTraceEnabled() ) {
            LOG.trace("add reactor for ctx({})/key({})", ctx, this._key);
        }
        this._reactors.put(idx, Pair.of(ctx, reactor));
        return this.currentEventHandler();
    }
    
    @OnEvent(event="removeReactor")
    private BizStep onRemoveReactor(final int idx) 
            throws Exception {
        if ( LOG.isTraceEnabled() ) {
            LOG.trace("remove reactor for idx({})/key({})", idx, this._key);
        }
        this._reactors.remove(idx);
        return this.currentEventHandler();
    }
    
    @OnEvent(event="onFetchComplete")
    private BizStep onFetchComplete(final CTX ctx, final VALUE value) {
        if ( LOG.isTraceEnabled() ) {
            LOG.trace("onFetchComplete: receive value({}) for ctx({})/key({})", value, ctx, this._key);
        }
        if ( null != value ) {
            this._cache.put(new Element(this._key, value));
            this.setEndReason("fetchAndCache.succeed");
        }
        else {
            this.setEndReason("fetchAndCache.failed.novalue");
        }
        for ( Pair<CTX, FetchReactor<CTX, VALUE>> pair : this._reactors.values()) {
            try {
                pair.second.onFetchComplete(pair.first, value);
            }
            catch (Throwable e) {
                LOG.warn("exception when FetchReactor.onFetchResult for ctx({})/key({}), detail:{}",
                        pair.first, this._key, ExceptionUtils.exception2detail(e));
            }
        }
        this._reactors.clear();
        return null;
    }
    
    public EventReceiver selfEventReceiver() {
        return super.selfEventReceiver();
    }

    private final FetchAgent<KEY, CTX, VALUE> _fetchAgent;
    private final KEY _key;
    private final Map<Integer, Pair<CTX, FetchReactor<CTX, VALUE>>> _reactors = 
            new HashMap<Integer, Pair<CTX, FetchReactor<CTX, VALUE>>>();
    private final Cache _cache;
}
