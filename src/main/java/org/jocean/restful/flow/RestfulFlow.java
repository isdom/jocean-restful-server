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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.FlowStateChangedListener;
import org.jocean.event.api.PairedGuardEventable;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.http.HttpRequestWrapper;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSource;
import org.jocean.idiom.Pair;
import org.jocean.idiom.Visitor;
import org.jocean.json.JSONProvider;
import org.jocean.netty.NettyUtils;
import org.jocean.restful.Events;
import org.jocean.restful.OutputReactor;
import org.jocean.restful.OutputSource;
import org.jocean.restful.Registrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;

/**
 * @author isdom
 * 
 */
public class RestfulFlow extends AbstractFlow<RestfulFlow> {

    private static final String APPLICATION_JSON_CHARSET_UTF_8 = 
            "application/json; charset=UTF-8";

    private static final Logger LOG = LoggerFactory
            .getLogger(RestfulFlow.class);
    
    private static final PairedGuardEventable ONFILEUPLOAD_EVENT = 
            new PairedGuardEventable(NettyUtils._NETTY_REFCOUNTED_GUARD, Events.ON_FILEUPLOAD);

    @Override
    public String toString() {
        return "RestfulFlow [httpRequest=" + _requestWrapper + "]";
    }

    private void destructor() throws Exception {
    	this._requestWrapper.clear();
    	if (null!=this._postDecoder) {
    	    this._postDecoder.destroy();
    	    this._postDecoder = null;
    	}
    }
    
    public RestfulFlow(
            final Registrar<?>  registrar,
            final JSONProvider  jsonProvider) {
        this._registrar = registrar;
        this._jsonProvider = jsonProvider;
        this.addFlowLifecycleListener(
    		new FlowLifecycleListener() {
				@Override
	            public void afterEventReceiverCreated(final EventReceiver receiver)
	                    throws Exception {}
	            @Override
	            public void afterFlowDestroy()
	                    throws Exception {
	                destructor();
	            }});
        this.addFlowStateChangedListener(
        		new FlowStateChangedListener<BizStep>() {
    		@Override
    		public void onStateChanged(
    				final BizStep prev,
    				final BizStep next, 
    				final String causeEvent, 
    				final Object[] causeArgs)
    				throws Exception {
    	    	if (LOG.isDebugEnabled()) {
    	    		LOG.debug("onStateChanged: prev:{} next:{} event:{}", prev, next, causeEvent);
    	    	}
                if (WAIT_FOR_TASK==next && !_isRequestHandled) {
                    LOG.warn("request {} not handled, so detach", _requestWrapper);
                    selfEventReceiver().acceptEvent("detach");
                }
    			if (null==next && "detach".equals(causeEvent)) {
    				// means flow end by detach event
    	            safeDetachTask();
    	            LOG.warn("SOURCE_CANCELED\ncost:[{}]s\nrequest:[{}]",
    	                    -1, _requestWrapper);
    	            setEndReason("restful.SOURCE_CANCELED");
    			}
    		}});
    }
    
    public RestfulFlow attach(
            final ChannelHandlerContext channelCtx,
            final HttpRequest httpRequest) {
        this._requestWrapper.setHttpRequest(httpRequest);
        this._channelCtx = channelCtx;
        return this;
    }
    
    private final Object ONDETACH = new Object() {
        @OnEvent(event = "detach")
        private BizStep onDetach() throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("business for channel:{}/uri:{} progress canceled", _channelCtx.channel());
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
    };

    public BizStep initBizStep() {
        final HttpRequest request = this._requestWrapper.request();
        if ( request.getMethod().equals(HttpMethod.POST)
                && HttpPostRequestDecoder.isMultipart(request)) {
            return initBizStepForMultipartPost();
        } else {
            return initBizStepStandard();
        }
        
    }

    private BizStep initBizStepForMultipartPost() {
        try {
            this._postDecoder = new HttpPostRequestDecoder(
                    HTTP_DATA_FACTORY, this._requestWrapper.request());
        } catch (Exception e) {
            //  TODO
            //  handle exception
            return  null;
        }
        return _requestWrapper.recvFullContentThenGoto(
                "restful.MULTIPART.RECVCONTENT",
                new Visitor<HttpContent>() {
                    @Override
                    public void visit(final HttpContent content) throws Exception {
                        foreachContent(content);
                    }},
                new Runnable() {
                    @Override
                    public void run() {
                        if (null!=_receiver) {
                            _receiver.acceptEvent(Events.ON_FILEUPLOAD_COMPLETED);
                        }
                    }},
                WAIT_FOR_TASK,
                ONDETACH);
    }

    private void foreachContent(final HttpContent content) {
        try {
            this._postDecoder.offer(content);
        } catch (ErrorDataDecoderException e) {
            //  TODO
        }
        try {
            while (this._postDecoder.hasNext()) {
                final InterfaceHttpData data = this._postDecoder.next();
                if (data != null) {
                    try {
                        processHttpData(data);
                    } finally {
                        data.release();
                    }
                }
            }
        } catch (EndOfDataDecoderException e) {
            //  TODO
        }
    }
    
    private void processHttpData(final InterfaceHttpData data) {
        if (data.getHttpDataType().equals(
                InterfaceHttpData.HttpDataType.FileUpload)) {
            final FileUpload fileUpload = (FileUpload)data;
            if (null==this._receiver) {
                final ByteBuf content = getContent(fileUpload);
                try {
                    _isRequestHandled = 
                        createAndInvokeRestfulBusiness(
                            _requestWrapper.request(), content, Multimaps.asMap(this._formParameters));
                } catch (Exception e) {
                    LOG.warn("exception when createAndInvokeRestfulBusiness, detail:{}",
                            ExceptionUtils.exception2detail(e));
                }
                if (null!=this._receiver && !isJson(fileUpload)) {
                    this._receiver.acceptEvent(ONFILEUPLOAD_EVENT, fileUpload);
                }
            } else {
                this._receiver.acceptEvent(ONFILEUPLOAD_EVENT, fileUpload);
            }
        } else if (data.getHttpDataType().equals(
                InterfaceHttpData.HttpDataType.Attribute)) {
            final Attribute attribute = (Attribute) data;
            try {
                this._formParameters.put(attribute.getName(), attribute.getValue());
            } catch (IOException e) {
                LOG.warn("exception when add form parameters for attr({}), detail: {}", 
                        attribute, ExceptionUtils.exception2detail(e));
            }
        } else {
            LOG.warn("not except HttpData:{}, just ignore.", data);
        }
    }

    private static ByteBuf getContent(final FileUpload fileUpload) {
        return isJson(fileUpload) 
                ? fileUpload.content()
                : Unpooled.EMPTY_BUFFER;
    }

    private static boolean isJson(final FileUpload fileUpload) {
        return fileUpload.getContentType().startsWith("application/json");
    }

    private BizStep initBizStepStandard() {
        return _requestWrapper.recvFullContentThenGoto(
                "restful.RECVCONTENT",
                null,
                new Runnable() {
                    @Override
                    public void run() {
                        final ByteBuf content = _requestWrapper.retainFullContent();
                        try {
                            _isRequestHandled = 
                                createAndInvokeRestfulBusiness(_requestWrapper.request(), content, 
                                    Multimaps.asMap(_formParameters));
                        } catch (Exception e) {
                            LOG.warn("exception when createAndInvokeRestfulBusiness, detail:{}",
                                    ExceptionUtils.exception2detail(e));
                        }
                        finally {
                            content.release();
                        }
                    }},
                WAIT_FOR_TASK,
                ONDETACH);
    }

    private final BizStep WAIT_FOR_TASK = new BizStep("restful.WAIT_FOR_TASK") {
        @OnEvent(event = "complete")
        private BizStep complete() {
            setEndReason("restful.complete");
            return null;
        }
    }
    .handler(handlersOf(ONDETACH))
    .freeze();
    
    private void notifyTaskComplete() {
        selfEventReceiver().acceptEvent("complete");
    }

    private boolean createAndInvokeRestfulBusiness(
            final HttpRequest request, final ByteBuf content, final Map<String, List<String>> formParameters) 
            throws Exception {
        final Pair<Object, String> flowAndEvent =
                this._registrar.buildFlowMatch(request, content, formParameters);

        if (null == flowAndEvent) {
            // path not found
            writeAndFlushResponse(null, null);
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
                    writeAndFlushResponse(responseJson, APPLICATION_JSON_CHARSET_UTF_8);
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
                    writeAndFlushResponse(responseJson, APPLICATION_JSON_CHARSET_UTF_8);
                    notifyTaskComplete();
                }

                @Override
                public void outputAsContentType(
                        final Object representation,
                        final String contentType) {
                    safeDetachTask();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("send resp:{}", representation);
                    }
                    writeAndFlushResponse(representation.toString(), contentType);
                    notifyTaskComplete();
                }

                @Override
                public void outputAsHttpResponse(final FullHttpResponse response) {
                    safeDetachTask();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("send resp:{}", response);
                    }
                    boolean keepAlive = isKeepAlive(_requestWrapper.request());
                    if (!keepAlive) {
                        // Write the response and close.
                        _channelCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                    } else {
                        _channelCtx.writeAndFlush(response);
                    }
                    notifyTaskComplete();
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

    private boolean writeAndFlushResponse(final String content, final String contentType) {
        // Decide whether to close the connection or not.
        boolean keepAlive = isKeepAlive(this._requestWrapper.request());
        // Build the response object.
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1, (null != content ? OK : NO_CONTENT),
                (null != content ? Unpooled.copiedBuffer(content, CharsetUtil.UTF_8) : Unpooled.buffer(0)));

        if (null != content) {
            response.headers().set(CONTENT_TYPE, contentType);
        }

        if (keepAlive) {
            // Add 'Content-Length' header only for a keep-alive connection.
            response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
            // Add keep alive header as per:
            // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        }

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

    private ChannelHandlerContext _channelCtx;
    private boolean _isRequestHandled = false;
    
    private final HttpRequestWrapper _requestWrapper = new HttpRequestWrapper();
    private HttpPostRequestDecoder _postDecoder;
    private static final HttpDataFactory HTTP_DATA_FACTORY =
            new DefaultHttpDataFactory(false);  // DO NOT use Disk
    private EventReceiver _receiver;
    private final ListMultimap<String,String> _formParameters = ArrayListMultimap.create();
    private final Registrar<?> _registrar;
    private Detachable  _task = null;
    private final JSONProvider _jsonProvider;
}
