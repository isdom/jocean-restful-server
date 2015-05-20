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
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.PairedGuardEventable;
import org.jocean.http.server.HttpTrade;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSource;
import org.jocean.idiom.Pair;
import org.jocean.json.JSONProvider;
import org.jocean.netty.NettyUtils;
import org.jocean.restful.Events;
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

    private static final PairedGuardEventable ONFILEUPLOAD_EVENT = 
            new PairedGuardEventable(NettyUtils._NETTY_REFCOUNTED_GUARD, Events.ON_FILEUPLOAD);

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
            private final ListMultimap<String,String> _formParameters = ArrayListMultimap.create();
            private Detachable _task = null;
            private EventReceiver _receiver;
            private boolean _isMultipart = false;
            private HttpPostRequestDecoder _postDecoder;
            @SuppressWarnings("unused")
            private boolean _isRequestHandled = false;
            private HttpRequest _request;
          
            private void destructor() {
                if (null!=this._postDecoder) {
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
            public void onError(Throwable e) {
                destructor();
            }
            
            private void onCompleted4Multipart() {
                if (null!=this._receiver) {
                    this._receiver.acceptEvent(Events.ON_FILEUPLOAD_COMPLETED);
                }
            }

            private void onCompleted4Standard() {
                final FullHttpRequest req = trade.retainFullHttpRequest();
                if (null!=req) {
                    try {
                        this._isRequestHandled =
                            createAndInvokeRestfulBusiness(
                                    trade, 
                                    req, 
                                    req.content(), 
                                    Multimaps.asMap(this._formParameters));
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
    
    private final HttpDataFactory HTTP_DATA_FACTORY =
            new DefaultHttpDataFactory(false);  // DO NOT use Disk
    private final Registrar<?> _registrar;
    private final JSONProvider _jsonProvider;
}
