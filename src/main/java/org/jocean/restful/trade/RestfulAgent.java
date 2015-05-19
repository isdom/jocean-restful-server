/**
 * 
 */
package org.jocean.restful.trade;

import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.CharsetUtil;

import java.util.List;
import java.util.Map;

import org.jocean.event.api.EventReceiver;
import org.jocean.http.server.HttpTrade;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSource;
import org.jocean.idiom.Pair;
import org.jocean.json.JSONProvider;
import org.jocean.restful.OutputReactor;
import org.jocean.restful.OutputSource;
import org.jocean.restful.Registrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscriber;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;

/**
 * @author isdom
 *
 */
public class RestfulAgent extends Subscriber<HttpTrade> {

    private static final Logger LOG =
            LoggerFactory.getLogger(RestfulAgent.class);

    public RestfulAgent(
            final Registrar<?>  registrar,
            final JSONProvider  jsonProvider) {
        this._registrar = registrar;
        this._jsonProvider = jsonProvider;
    }
    
    @Override
    public void onCompleted() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onError(final Throwable e) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onNext(final HttpTrade trade) {
        trade.request().subscribe(new Subscriber<HttpObject>() {
          private Detachable  _task = null;
          private EventReceiver _receiver;
          private final ListMultimap<String,String> _formParameters = ArrayListMultimap.create();
          
            @Override
            public void onCompleted() {
                final FullHttpRequest req = trade.retainFullHttpRequest();
                if (null!=req) {
                    try {
                        createAndInvokeRestfulBusiness(trade, req, req.content(), 
                            Multimaps.asMap(_formParameters)
                                );
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            @Override
            public void onError(Throwable e) {
            }
            @Override
            public void onNext(final HttpObject msg) {
            }
            private boolean createAndInvokeRestfulBusiness(
                    final HttpTrade trade,
                    final HttpRequest request, 
                    final ByteBuf content, 
                    final Map<String, List<String>> formParameters) 
                    throws Exception {
                final Pair<Object, String> flowAndEvent =
                        _registrar.buildFlowMatch(request, content, formParameters);

                if (null == flowAndEvent) {
                    // path not found
                    writeAndFlushResponse(trade, request, null);
                    return false;
                }

                final InterfaceSource flow = (InterfaceSource) flowAndEvent.getFirst();
                this._task = flow.queryInterfaceInstance(Detachable.class);

                try {
                    ((OutputSource) flow).setOutputReactor(new OutputReactor() {
                        @Override
                        public void output(final Object representation) {
                            safeDetachTask();
                            final String responseJson = _jsonProvider.toJSONString(representation);
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("send resp:{}", responseJson);
                            }
                            writeAndFlushResponse(trade, request, responseJson);
//                            notifyTaskComplete();
                        }

                        @Override
                        public void output(final Object representation, final String outerName) {
                            safeDetachTask();
                            final String responseJson = 
                                    outerName + "(" + _jsonProvider.toJSONString(representation) + ")";
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("send resp:{}", responseJson);
                            }
                            writeAndFlushResponse(trade, request, responseJson);
//                            notifyTaskComplete();
                        }
                    });
                } catch (Exception e) {
                    LOG.warn("exception when call flow({})'s setOutputReactor, detail:{}",
                            flow, ExceptionUtils.exception2detail(e));
                }
                this._receiver = flow.queryInterfaceInstance(EventReceiver.class);
                this._receiver.acceptEvent(flowAndEvent.getSecond());

                return true;
            }
            
            private void safeDetachTask() {
                if (null != this._task) {
                    try {
                        this._task.detach();
                    } catch (Exception e) {
                        LOG.warn("exception when detach current flow, detail:{}",
                                ExceptionUtils.exception2detail(e));
                    }
                    this._task = null;
                }
            }
            }
        );
    }
    
    private boolean writeAndFlushResponse(final HttpTrade trade, final HttpRequest request, final String content) {
        // Decide whether to close the connection or not.
        boolean keepAlive = isKeepAlive(request);
        // Build the response object.
        final FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1, (null != content ? OK : NO_CONTENT),
                (null != content ? Unpooled.copiedBuffer(content, CharsetUtil.UTF_8) : Unpooled.buffer(0)));

        response.headers().set(CONTENT_TYPE, "application/json; charset=UTF-8");

        if (keepAlive) {
            // Add 'Content-Length' header only for a keep-alive connection.
            response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
            // Add keep alive header as per:
            // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        }

        response.headers().set(HttpHeaders.Names.CACHE_CONTROL, HttpHeaders.Values.NO_STORE);
        response.headers().set(HttpHeaders.Names.PRAGMA, HttpHeaders.Values.NO_CACHE);

        trade.response(Observable.<HttpObject>just(response));

        return keepAlive;
    }
    
    private final Registrar<?> _registrar;
    private final JSONProvider _jsonProvider;
}
