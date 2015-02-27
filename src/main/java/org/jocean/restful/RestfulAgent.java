/**
 * 
 */
package org.jocean.restful;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.api.EventUtils;
import org.jocean.httpserver.ServerAgent;
import org.jocean.restful.flow.RestfulFlow;

/**
 * @author isdom
 *
 */
public abstract class RestfulAgent implements ServerAgent {

    public RestfulAgent(final EventReceiverSource source) {
        this._source = source;
    }
    
    @Override
    public ServerTask createServerTask(
            final ChannelHandlerContext channelCtx, 
            final HttpRequest httpRequest) {
        return EventUtils.buildInterfaceAdapter(ServerTask.class,  
            this._source.createFromInnerState(
                createRestfulFlow().attach(channelCtx, httpRequest).INIT));
    }
    
    protected abstract RestfulFlow createRestfulFlow();
    
    private final EventReceiverSource _source;
}
