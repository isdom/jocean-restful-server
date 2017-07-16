/**
 * 
 */
package org.jocean.restful.trade;

import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.event.api.EventReceiver;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.AsBlob;
import org.jocean.http.util.InboundSpeedController;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSource;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.j2se.jmx.MBeanRegister;
import org.jocean.j2se.jmx.MBeanRegisterAware;
import org.jocean.json.JSONProvider;
import org.jocean.netty.BlobRepo.Blob;
import org.jocean.netty.util.ReferenceCountedHolder;
import org.jocean.restful.BlobSource;
import org.jocean.restful.BlobSourceAware;
import org.jocean.restful.OutputReactor;
import org.jocean.restful.OutputSource;
import org.jocean.restful.Registrar;
import org.jocean.restful.TradeInboundAware;
import org.jocean.restful.mbean.TradeProcessorMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class TradeProcessor extends Subscriber<HttpTrade> 
    implements TradeProcessorMXBean, MBeanRegisterAware, BeanHolderAware  {

    private static final Logger LOG =
            LoggerFactory.getLogger(TradeProcessor.class);

    private static final String APPLICATION_JSON_CHARSET_UTF_8 = 
            "application/json; charset=UTF-8";
    
    public TradeProcessor(
            final Registrar<?>  registrar,
            final JSONProvider  jsonProvider) {
        this._registrar = registrar;
        this._jsonProvider = jsonProvider;
    }
    
    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    public void destroy() {
        //  clean up all leak HttpDatas
        HTTP_DATA_FACTORY.cleanAllHttpData();
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
        if ( null != this._beanHolder) {
            final InboundSpeedController isc = 
                    _beanHolder.getBean(InboundSpeedController.class);
            // TBD: replace by new ISC
//            if (null != isc) {
//                isc.applyTo(trade.inbound());
//            }
        }
        trade.inbound().subscribe(
            buildInboundSubscriber(trade, 
            trade.inboundHolder().fullOf(RxNettys.BUILD_FULL_REQUEST)));
    }

    private Subscriber<HttpObject> buildInboundSubscriber(
            final HttpTrade trade,
            final Func0<FullHttpRequest> buildFullReq) {
        return new Subscriber<HttpObject>() {
            private final ListMultimap<String,String> _formParameters = ArrayListMultimap.create();
            private Detachable _task = null;
            private EventReceiver _receiver;
            private boolean _isMultipart = false;
            private HttpPostRequestDecoder _postDecoder;
            @SuppressWarnings("unused")
            private boolean _isRequestHandled = false;
            private HttpRequest _request;
          
            private void destructor() {
                destroyPostDecoder();
            }

            private void destroyPostDecoder() {
                if (null!=this._postDecoder) {
                    // HttpPostRequestDecoder's destroy call HttpDataFactory.cleanRequestHttpDatas
                    //  so no need to cleanRequestHttpDatas outside
                    this._postDecoder.destroy();
                    this._postDecoder = null;
                }
            }
            
            @Override
            public void onCompleted() {
                if (this._isMultipart) {
                    onCompleted4Multipart();
                } else {
                    onCompleted4Standard();
                }
                destructor();
            }

            @Override
            public void onError(final Throwable e) {
                safeDetachTask();
                LOG.warn("SOURCE_CANCELED\nfor cause:[{}]", 
                        ExceptionUtils.exception2detail(e));
                destructor();
            }
            
            private void onCompleted4Multipart() {
            }

            private void onCompleted4Standard() {
                final FullHttpRequest req = buildFullReq.call();
                if (null!=req) {
                    try {
                        final String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
                        if (isPostWithForm(req)) {
                            final String queryString = toQueryString(req.content());
                            this._isRequestHandled =
                                createAndInvokeRestfulBusiness(
                                        trade, 
                                        trade.inbound(),
                                        req, 
                                        contentType,
                                        req.content(), 
                                        null != queryString 
                                            ? new QueryStringDecoder(queryString, false).parameters()
                                            : null);
                        } else {
                            this._isRequestHandled =
                                createAndInvokeRestfulBusiness(
                                        trade, 
                                        trade.inbound(),
                                        req, 
                                        contentType,
                                        req.content(), 
                                        Multimaps.asMap(this._formParameters));
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when createAndInvokeRestfulBusiness, detail:{}",
                                ExceptionUtils.exception2detail(e));
                    } finally {
                        req.release();
                    }
                }
            }
            
            @Override
            public void onNext(final HttpObject msg) {
                if (msg instanceof HttpRequest) {
                    this._request = (HttpRequest)msg;
                    if ( this._request.method().equals(HttpMethod.POST)
                            && HttpPostRequestDecoder.isMultipart(this._request)) {
                        this._isMultipart = true;
                        this._postDecoder = new HttpPostRequestDecoder(
                                HTTP_DATA_FACTORY, this._request);
                    } else {
                        this._isMultipart = false;
                    }
                }
                if (msg instanceof HttpContent && this._isMultipart) {
                    onNext4Multipart((HttpContent)msg);
                }
            }

            private void onNext4Multipart(HttpContent content) {
                if (null!=this._postDecoder) {
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
                                    if ( !processHttpData(data) ) {
                                        destroyPostDecoder();
                                        break;
                                    }
                                } finally {
                                    data.release();
                                }
                            }
                        }
                    } catch (EndOfDataDecoderException e) {
                        //  TODO
                    }
                }
            }
            
            private boolean processHttpData(final InterfaceHttpData data) {
                if (data.getHttpDataType().equals(
                        InterfaceHttpData.HttpDataType.FileUpload)) {
                    final FileUpload fileUpload = (FileUpload)data;
                    if (null==this._receiver) {
                        final ByteBuf content = getContent(fileUpload);
                        try {
                            this._isRequestHandled = 
                                createAndInvokeRestfulBusiness(
                                        trade,
                                        trade.inbound(),
                                        this._request, 
                                        fileUpload.getContentType(),
                                        content, 
                                        Multimaps.asMap(this._formParameters));
                        } catch (Exception e) {
                            LOG.warn("exception when createAndInvokeRestfulBusiness, detail:{}",
                                    ExceptionUtils.exception2detail(e));
                        }
                        return false;
//                        if (null!=this._receiver && !isJson(fileUpload)) {
//                            this._receiver.acceptEvent(ONFILEUPLOAD_EVENT, fileUpload);
//                        }
                    } else {
                        return false;
                    }
//                    else {
//                        this._receiver.acceptEvent(ONFILEUPLOAD_EVENT, fileUpload);
//                    }
                } else if (data.getHttpDataType().equals(
                        InterfaceHttpData.HttpDataType.Attribute)) {
                    final Attribute attribute = (Attribute) data;
                    try {
                        this._formParameters.put(attribute.getName(), attribute.getValue());
                    } catch (IOException e) {
                        LOG.warn("exception when add form parameters for attr({}), detail: {}", 
                                attribute, ExceptionUtils.exception2detail(e));
                    }
                    return true;
                } else {
                    LOG.warn("not except HttpData:{}, just ignore.", data);
                    return false;
                }
            }
            
            private ByteBuf getContent(final FileUpload fileUpload) {
                return isJson(fileUpload) 
                        ? fileUpload.content()
                        : Unpooled.EMPTY_BUFFER;
            }

            private boolean isJson(final FileUpload fileUpload) {
                return fileUpload.getContentType().startsWith("application/json");
            }
            
            private boolean createAndInvokeRestfulBusiness(
                    final HttpTrade trade,
                    final Observable<? extends HttpObject> cached, 
                    final HttpRequest request, 
                    final String  contentType,
                    final ByteBuf content, 
                    final Map<String, List<String>> formParameters) 
                    throws Exception {
                final Pair<Object, String> flowAndEvent =
                        _registrar.buildFlowMatch(request, contentType, content, formParameters);

                if (null == flowAndEvent) {
                    // path not found
                    writeAndFlushResponse(trade, request, null, null, null);
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
                            LOG.info("RESTFUL_Trade_Summary: recv req:{}, and sendback resp:{}", request, responseJson);
                            writeAndFlushResponse(trade, request, representation, responseJson, _defaultContentType);
                        }

                        @Override
                        public void output(final Object representation, final String outerName) {
                            safeDetachTask();
                            final String responseJson = 
                                    outerName + "(" + _jsonProvider.toJSONString(representation) + ")";
                            LOG.info("RESTFUL_Trade_Summary: recv req:{}, and sendback resp:{}({})", request, outerName, responseJson);
                            writeAndFlushResponse(trade, request, representation, responseJson, _defaultContentType);
                        }

                        @Override
                        public void outputAsContentType(
                                final Object representation,
                                final String contentType) {
                            safeDetachTask();
                            LOG.info("RESTFUL_Trade_Summary: recv req:{}, and sendback resp with contentType({}):{}", 
                                    request, contentType, representation);
                            writeAndFlushResponse(trade, request, representation, representation.toString(), contentType);
                        }
                        
                        @Override
                        public void outputAsHttpResponse(final FullHttpResponse response) {
                            safeDetachTask();
                            LOG.info("RESTFUL_Trade_Summary: recv req:{}, and sendback http-resp:{}", 
                                    request, response);
                            final boolean keepAlive = HttpUtil.isKeepAlive(request);
                            if (keepAlive) {
                                // Add keep alive header as per:
                                // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
                                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                            }

                            addExtraHeaders(response);

                            trade.outbound(Observable.<HttpObject>just(response));
                        }
                    });
                } catch (Exception e) {
                    LOG.warn("exception when call flow({})'s setOutputReactor, detail:{}",
                            flow, ExceptionUtils.exception2detail(e));
                }
                if (flow instanceof TradeInboundAware) {
                    ((TradeInboundAware)flow).setTradeInbound(cached);
                }
                if (flow instanceof BlobSourceAware) {
                    ((BlobSourceAware)flow).setBlobSource(buildBlobSource(trade));
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
        };
    }
    
    private BlobSource buildBlobSource(final HttpTrade trade) {
        final ReferenceCountedHolder holder = new ReferenceCountedHolder();
        trade.doOnTerminate(holder.release());
        
        return new BlobSource() {
            @Override
            public Observable<? extends Blob> toBlobs(
                    final String contentTypePrefix,
                    final boolean releaseRequestASAP) {
                final AsBlob asBlob = new AsBlob(contentTypePrefix, 
                        holder, 
                        releaseRequestASAP ? trade.inboundHolder() : null);
                // 设定writeIndex >= 128K 时，即可 尝试对 undecodedChunk 进行 discardReadBytes()
                asBlob.setDiscardThreshold(128 * 1024);
                trade.doOnTerminate(asBlob.destroy());
                
                final AtomicInteger _lastAddedSize = new AtomicInteger(0);
                trade.doOnTerminate(new Action0() {
                    @Override
                    public void call() {
                        updateCurrentUndecodedSize(-_lastAddedSize.getAndSet(-1));
                    }});
                
                return trade.inbound()
                    .doOnNext(new Action1<HttpObject>() {
                        @Override
                        public void call(final HttpObject obj) {
                            final int currentsize = asBlob.currentUndecodedSize();
                            final int lastsize = _lastAddedSize.getAndSet(currentsize);
                            if (lastsize >= 0) { // -1 means trade has closed
                                updateCurrentUndecodedSize(currentsize - lastsize);
                            } else {
                                //  TODO? set lastsize (== -1) back to _lastAddedSize ?
                            }
                        }})
                    .flatMap(asBlob)
                    .compose(RxObservables.<Blob>ensureSubscribeAtmostOnce());
            }

            @Override
            public Action1<Blob> releaseBlob() {
                return new Action1<Blob>() {
                    @Override
                    public void call(final Blob blob) {
                        holder.releaseReferenceCounted(blob);
                    }};
            }};
    }

    private boolean writeAndFlushResponse(
            final HttpTrade trade, 
            final HttpRequest request, 
            final Object respBean, 
            final String content, 
            final String contentType) {
        // Decide whether to close the connection or not.
        final boolean keepAlive = HttpUtil.isKeepAlive(request);
        // Build the response object.
        final FullHttpResponse response = new DefaultFullHttpResponse(
                request.protocolVersion(), 
                (null != content ? OK : NOT_FOUND),
                (null != content ? Unpooled.copiedBuffer(content, CharsetUtil.UTF_8) : Unpooled.buffer(0)));

        if (null != content) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }

        // Add 'Content-Length' header only for a keep-alive connection.
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        
        if (keepAlive) {
            // Add keep alive header as per:
            // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE);
        response.headers().set(HttpHeaderNames.PRAGMA, HttpHeaderValues.NO_CACHE);

        addExtraHeaders(response);
        
        if (null != respBean) {
            this._respProcessors.get(respBean.getClass()).call(respBean, response);
        }
        
        trade.outbound(Observable.<HttpObject>just(response));

        return keepAlive;
    }

    private void addExtraHeaders(final FullHttpResponse response) {
        if (null!=this._extraHeaders) {
            for (Map.Entry<String, String> entry : this._extraHeaders.entrySet()) {
                response.headers().set(entry.getKey(), entry.getValue());
            }
        }
    }
    
    private static String toQueryString(final ByteBuf content)
            throws UnsupportedEncodingException, IOException {
        if (content instanceof EmptyByteBuf) {
            return null;
        }
        return new String(ByteStreams.toByteArray(new ByteBufInputStream(content.slice())),
                "UTF-8");
    }

    private static boolean isPostWithForm(final FullHttpRequest req) {
        return req.method().equals(HttpMethod.POST)
          && req.headers().contains(HttpHeaderNames.CONTENT_TYPE)
          && req.headers().get(HttpHeaderNames.CONTENT_TYPE)
              .startsWith(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString());
    }

    public void setDefaultContentType(final String defaultContentType) {
        this._defaultContentType = defaultContentType;
    }
    
    public void setExtraHeaders(final Map<String, String> extraHeaders) {
        this._extraHeaders = extraHeaders;
    }

    private final HttpDataFactory HTTP_DATA_FACTORY =
            new DefaultHttpDataFactory(false);  // DO NOT use Disk
    private final Registrar<?> _registrar;
    private final JSONProvider _jsonProvider;
    
    private BeanHolder _beanHolder;
    
    private String _defaultContentType = APPLICATION_JSON_CHARSET_UTF_8;
    private Map<String, String> _extraHeaders;
    private final SimpleCache<Class<?>, ResponseProcessor> _respProcessors
        = new SimpleCache<>(new Func1<Class<?>, ResponseProcessor>() {
            @Override
            public ResponseProcessor call(final Class<?> clsResponse) {
                return new ResponseProcessor(clsResponse);
            }});

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        register.registerMBean("name=tradeProcessor", this);
    }

    @Override
    public int getCurrentUndecodedSize() {
        return this._currentUndecodedSize.get();
    }
    
    @Override
    public int getPeakUndecodedSize() {
        return this._peakUndecodedSize.get();
    }
    
    private final AtomicInteger  _currentUndecodedSize = new AtomicInteger(0);
    private final AtomicInteger  _peakUndecodedSize = new AtomicInteger(0);

    private void updateCurrentUndecodedSize(final int delta) {
        final int current = this._currentUndecodedSize.addAndGet(delta);
        if (delta > 0) {
            boolean updated = false;
            
            do {
                // try to update peak memory value
                final int peak = this._peakUndecodedSize.get();
                if (current > peak) {
                    updated = this._peakUndecodedSize.compareAndSet(peak, current);
                } else {
                    break;
                }
            } while (!updated);
        }
    }
}
