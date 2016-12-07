package org.jocean.restful;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;

public interface TradeInboundAware {
    public void setTradeInbound(final Observable<? extends HttpObject> inboundRequest);

}
