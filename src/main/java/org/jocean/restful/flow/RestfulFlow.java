/**
 * 
 */
package org.jocean.restful.flow;

import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.InputStream;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSource;
import org.jocean.idiom.Pair;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.block.BlockUtils;
import org.jocean.idiom.block.PooledBytesOutputStream;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.json.JSONProvider;
import org.jocean.restful.OutputReactor;
import org.jocean.restful.OutputSource;
import org.jocean.restful.Registrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 * 
 */
public class RestfulFlow extends AbstractFlow<RestfulFlow> {

    private static final Logger LOG = LoggerFactory
            .getLogger(RestfulFlow.class);
    
    @Override
    public String toString() {
        return "RestfulFlow [httpRequest=" + _httpRequest + "]";
    }

    private static final FlowLifecycleListener<RestfulFlow> LIFECYCLE_LISTENER = 
            new FlowLifecycleListener<RestfulFlow>() {
        @Override
        public void afterEventReceiverCreated(
                final RestfulFlow flow, final EventReceiver receiver)
                throws Exception {
            receiver.acceptEvent("start");
        }
        @Override
        public void afterFlowDestroy(final RestfulFlow flow)
                throws Exception {
            flow.destructor();
        }
    };
    
    private void destructor() throws Exception {
        ReferenceCountUtil.safeRelease(this._httpRequest);
        this._output.close();
    }
    
    public RestfulFlow(
            final Registrar<?>  registrar,
            final BytesPool     bytesPool,
            final JSONProvider  jsonProvider) {
        this._registrar = registrar;
        this._jsonProvider = jsonProvider;
        this._output = new PooledBytesOutputStream(bytesPool);
        addFlowLifecycleListener(LIFECYCLE_LISTENER);
    }
    
    public RestfulFlow attach(
            final ChannelHandlerContext channelCtx,
            final HttpRequest httpRequest) {
        this._httpRequest = ReferenceCountUtil.retain(httpRequest);
        this._channelCtx = channelCtx;
        updateRecvHttpRequestState(this._httpRequest);
        byteBuf2OutputStream(this._httpRequest, this._output);
        return this;
    }
    
    private final class ONDETACH {
        public ONDETACH(final Runnable ifDetached) {
            this._ifDetached = ifDetached;
        }
        
        @OnEvent(event = "detach")
        private BizStep onDetach() throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("business progress canceled");
            }
            if (null != this._ifDetached) {
                try {
                    this._ifDetached.run();
                }
                catch(Throwable e) {
                    LOG.warn("exception when invoke {}, detail: {}", 
                            this._ifDetached, ExceptionUtils.exception2detail(e));
                }
            }
            try {
                _channelCtx.close();
            }
            catch(Throwable e) {
                LOG.warn("exception when close {}, detail: {}", 
                        _channelCtx, ExceptionUtils.exception2detail(e));
            }
            return null;
        }
        
        private final Runnable _ifDetached;
    }

    private static long byteBuf2OutputStream(final HttpObject httpObj, final PooledBytesOutputStream os) {
        long bytesCopied = 0;
        if (httpObj instanceof HttpContent) {
            try (InputStream is = new ByteBufInputStream(((HttpContent)httpObj).content())) {
                bytesCopied = BlockUtils.inputStream2OutputStream(is, os);
            } catch (IOException e) {
        }
        }
        return bytesCopied;
    }
    
    public final BizStep INIT = new BizStep("restful.INIT") {
        @OnEvent(event = "start")
        private BizStep start() throws Exception {

            if (isRecvHttpRequestComplete()) {
                createAndInvokeRestfulBusiness();
                return WAIT_FOR_TASK;
            }
            else {
                return  currentEventHandler();
            }
        }
        
        @OnEvent(event = "onHttpContent")
        private BizStep recvHttpContent(final HttpContent httpContent) throws Exception {
            byteBuf2OutputStream(httpContent, _output);
            
            updateRecvHttpRequestState(httpContent);
            if (isRecvHttpRequestComplete()) {
                createAndInvokeRestfulBusiness();
                return WAIT_FOR_TASK;
            }
            else {
                return currentEventHandler();
            }
        }
    }
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            LOG.warn("SOURCE_CANCELED\ncost:[{}]s\nrequest:[{}]",
                    -1, _httpRequest);
            setEndReason("restful.SOURCE_CANCELED");
        }})))
    .freeze();

    private final BizStep WAIT_FOR_TASK = new BizStep("restful.WAIT_FOR_TASK") {
        @OnEvent(event = "complete")
        private BizStep complete() {
            setEndReason("restful.complete");
            return null;
        }
    }
    .handler(handlersOf(new ONDETACH(new Runnable() {
        @Override
        public void run() {
            safeDetachTask();
            LOG.warn("SOURCE_CANCELED\ncost:[{}]s\nrequest:[{}]",
                    -1, _httpRequest);
            setEndReason("restful.SOURCE_CANCELED");
        }})))
    .freeze();
    
    private void notifyTaskComplete() {
        try {
            selfEventReceiver().acceptEvent("complete");
        } catch (Exception e) {
        }
    }

    private void updateRecvHttpRequestState(final HttpObject httpObject) {
        if ( (httpObject instanceof FullHttpRequest) 
            || (httpObject instanceof LastHttpContent)) {
            this._recvHttpRequestComplete = true;
        }
    }
    
    private boolean isRecvHttpRequestComplete() {
        return this._recvHttpRequestComplete;
    }

    private void createAndInvokeRestfulBusiness() throws Exception {
        
        final String contentType = this._httpRequest.headers().get(HttpHeaders.Names.CONTENT_TYPE);
        
        //final byte[] bytes = decodeContentOf(blob, contentType);
        try (final Blob blob = this._output.drainToBlob()) {
            invokeFlowOrResponseNoContent(blob, contentType, this._output);
        }
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

    /**
     * @param ctx
     * @param request
     * @throws Exception
     */
    private void invokeFlowOrResponseNoContent(
            final Blob blob,
            final String contentType,
            final PooledBytesOutputStream output) throws Exception {
        final Pair<Object, String> flowAndEvent =
                this._registrar.buildFlowMatch(
                        this._httpRequest.getMethod().name(), 
                        this._httpRequest.getUri(), 
                        this._httpRequest, 
                        blob, 
                        contentType, 
                        output);

        if (null == flowAndEvent) {
            // path not found
            writeAndFlushResponse(null);
            return;
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
                    writeAndFlushResponse(responseJson);
                    notifyTaskComplete();
                }

                @Override
                public void output(final Object representation, final String outerName) {
                    safeDetachTask();
                    final String responseJson = 
                            outerName + "(" + _jsonProvider.toJSONString(representation) + ")";
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("send resp:{}", responseJson);
                    }
                    writeAndFlushResponse(responseJson);
                    notifyTaskComplete();
                }
            });
        } catch (Exception e) {
            LOG.warn("exception when call flow({})'s setOutputReactor, detail:{}",
                    flow, ExceptionUtils.exception2detail(e));
        }

        flow.queryInterfaceInstance(EventReceiver.class)
                .acceptEvent(flowAndEvent.getSecond());
    }

    private boolean writeAndFlushResponse(final String content) {
        // Decide whether to close the connection or not.
        boolean keepAlive = isKeepAlive(this._httpRequest);
        // Build the response object.
        FullHttpResponse response = new DefaultFullHttpResponse(
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

        //  TODO
        //  暂时屏蔽 cookie 的写回，不设置任何cookie
        //  复杂cookie 会导致 nginx 作为前置代理时的处理超时问题。
        // Encode the cookie.
//        String cookieString = _request.headers().get(COOKIE);
//        if (cookieString != null) {
//            Set<Cookie> cookies = CookieDecoder.decode(cookieString);
//            if (!cookies.isEmpty()) {
//                // Reset the cookies if necessary.
//                for (Cookie cookie: cookies) {
//                    response.headers().add(SET_COOKIE, ServerCookieEncoder.encode(cookie));
//                }
//            }
//        } else {
//            // Browser sent no cookie.  Add some.
////            response.headers().add(SET_COOKIE, ServerCookieEncoder.encode("key1", "value1"));
////            response.headers().add(SET_COOKIE, ServerCookieEncoder.encode("key2", "value2"));
//        }
        response.headers().set(HttpHeaders.Names.CACHE_CONTROL, HttpHeaders.Values.NO_STORE);
        response.headers().set(HttpHeaders.Names.PRAGMA, HttpHeaders.Values.NO_CACHE);

        if (!keepAlive) {
            // Write the response and close.
            this._channelCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            this._channelCtx.writeAndFlush(response);
        }

        return keepAlive;
    }

    private HttpRequest _httpRequest;
    private ChannelHandlerContext _channelCtx;
    
    private boolean _recvHttpRequestComplete = false;
    
    private final Registrar<?> _registrar;
    private final PooledBytesOutputStream _output;
    private Detachable  _task = null;
    private final JSONProvider _jsonProvider;
}
