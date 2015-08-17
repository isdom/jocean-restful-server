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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.PairedGuardEventable;
import org.jocean.http.server.CachedRequest;
import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSource;
import org.jocean.idiom.Pair;
import org.jocean.json.JSONProvider;
import org.jocean.restful.Events;
import org.jocean.restful.OutputReactor;
import org.jocean.restful.OutputSource;
import org.jocean.restful.Registrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscriber;
import rx.observers.SerializedSubscriber;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.io.ByteStreams;

/**
 * @author isdom
 *
 */
public class RestfulSubscriber extends Subscriber<HttpTrade> {

    private static final Logger LOG =
            LoggerFactory.getLogger(RestfulSubscriber.class);

    private static final PairedGuardEventable ONFILEUPLOAD_EVENT = 
            new PairedGuardEventable(Nettys._NETTY_REFCOUNTED_GUARD, Events.ON_FILEUPLOAD);

    private static final String APPLICATION_JSON_CHARSET_UTF_8 = 
            "application/json; charset=UTF-8";
    
    public RestfulSubscriber(
            final Registrar<?>  registrar,
            final JSONProvider  jsonProvider) {
        this._registrar = registrar;
        this._jsonProvider = jsonProvider;
    }
    
    public void destroy() {
        //  clean up all leak HttpDatas
        HTTP_DATA_FACTORY.cleanAllHttpDatas();
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
        final Subscriber<HttpObject> subscriber = 
                new Subscriber<HttpObject>() {
            private final ListMultimap<String,String> _formParameters = ArrayListMultimap.create();
            private Detachable _task = null;
            private EventReceiver _receiver;
            private boolean _isMultipart = false;
            private HttpPostRequestDecoder _postDecoder;
            @SuppressWarnings("unused")
            private boolean _isRequestHandled = false;
            private HttpRequest _request;
            private CachedRequest _cached = new CachedRequest(trade);
          
            private void destructor() {
                if (null!=this._postDecoder) {
                    // HttpPostRequestDecoder's destroy call HttpDataFactory.cleanRequestHttpDatas
                    //  so no need to cleanRequestHttpDatas outside
                    this._postDecoder.destroy();
                    this._postDecoder = null;
                }
                this._cached.destroy();
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
                if (null!=this._receiver) {
                    this._receiver.acceptEvent(Events.ON_FILEUPLOAD_COMPLETED);
                }
            }

            private void onCompleted4Standard() {
                final FullHttpRequest req = this._cached.retainFullHttpRequest();
                if (null!=req) {
                    try {
                        if (isPostWithForm(req)) {
                            final String queryString = toQueryString(req.content());
                            this._isRequestHandled =
                                createAndInvokeRestfulBusiness(
                                        trade, 
                                        req, 
                                        Unpooled.EMPTY_BUFFER, 
                                        null != queryString 
                                            ? new QueryStringDecoder(queryString, false).parameters()
                                            : null);
                        } else {
                            this._isRequestHandled =
                                createAndInvokeRestfulBusiness(
                                        trade, 
                                        req, 
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
                    if ( this._request.getMethod().equals(HttpMethod.POST)
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
                            this._isRequestHandled = 
                                createAndInvokeRestfulBusiness(
                                        trade,
                                        this._request, 
                                        content, 
                                        Multimaps.asMap(this._formParameters));
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
                    final HttpRequest request, 
                    final ByteBuf content, 
                    final Map<String, List<String>> formParameters) 
                    throws Exception {
                final Pair<Object, String> flowAndEvent =
                        _registrar.buildFlowMatch(request, content, formParameters);

                if (null == flowAndEvent) {
                    // path not found
                    writeAndFlushResponse(trade, request, null, null);
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
                            writeAndFlushResponse(trade, request, responseJson, _defaultContentType);
                        }

                        @Override
                        public void output(final Object representation, final String outerName) {
                            safeDetachTask();
                            final String responseJson = 
                                    outerName + "(" + _jsonProvider.toJSONString(representation) + ")";
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("send resp:{}", responseJson);
                            }
                            writeAndFlushResponse(trade, request, responseJson, _defaultContentType);
                        }

                        @Override
                        public void outputAsContentType(
                                final Object representation,
                                final String contentType) {
                            safeDetachTask();
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("send resp:{}", representation);
                            }
                            writeAndFlushResponse(trade, request, representation.toString(), contentType);
                        }
                        
                        @Override
                        public void outputAsHttpResponse(final FullHttpResponse response) {
                            safeDetachTask();
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("send resp:{}", response);
                            }
                            final boolean keepAlive = isKeepAlive(request);
                            if (keepAlive) {
                                // Add keep alive header as per:
                                // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
                                response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
                            }

                            addExtraHeaders(response);

                            Observable.<HttpObject>just(response).subscribe(trade.responseObserver());
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
        };
        
        trade.request().subscribe(
            new SerializedSubscriber<HttpObject>(subscriber));
    }
    
    private boolean writeAndFlushResponse(
            final HttpTrade trade, 
            final HttpRequest request, 
            final String content, 
            final String contentType) {
        // Decide whether to close the connection or not.
        final boolean keepAlive = isKeepAlive(request);
        // Build the response object.
        final FullHttpResponse response = new DefaultFullHttpResponse(
                request.getProtocolVersion(), 
                (null != content ? OK : NO_CONTENT),
                (null != content ? Unpooled.copiedBuffer(content, CharsetUtil.UTF_8) : Unpooled.buffer(0)));

        if (null != content) {
            response.headers().set(CONTENT_TYPE, contentType);
        }

        // Add 'Content-Length' header only for a keep-alive connection.
        response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
        
        if (keepAlive) {
            // Add keep alive header as per:
            // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        }

        response.headers().set(HttpHeaders.Names.CACHE_CONTROL, HttpHeaders.Values.NO_STORE);
        response.headers().set(HttpHeaders.Names.PRAGMA, HttpHeaders.Values.NO_CACHE);

        addExtraHeaders(response);
        
        Observable.<HttpObject>just(response).subscribe(trade.responseObserver());

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
        return req.getMethod().equals(HttpMethod.POST)
          && req.headers().contains(HttpHeaders.Names.CONTENT_TYPE)
          && req.headers().get(HttpHeaders.Names.CONTENT_TYPE)
              .startsWith(HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
    
    private String _defaultContentType = APPLICATION_JSON_CHARSET_UTF_8;
    private Map<String, String> _extraHeaders;
}
