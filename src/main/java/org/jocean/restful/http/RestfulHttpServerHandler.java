package org.jocean.restful.http;

import com.jcraft.jzlib.Inflater;
import com.jcraft.jzlib.InflaterInputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.jocean.event.api.EventReceiver;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSource;
import org.jocean.idiom.Pair;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.block.BlockUtils;
import org.jocean.idiom.block.PooledBytesOutputStream;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.json.FastJSONProvider;
import org.jocean.json.JSONProvider;
import org.jocean.json.JacksonProvider;
import org.jocean.restful.OutputReactor;
import org.jocean.restful.OutputSource;
import org.jocean.restful.Registrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static io.netty.handler.codec.http.HttpHeaders.is100ContinueExpected;
import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class RestfulHttpServerHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger LOG
            = LoggerFactory.getLogger(RestfulHttpServerHandler.class);

    public RestfulHttpServerHandler(final Registrar registrar, final BytesPool bytesPool) {
        this._registrar = registrar;
        this._output = new PooledBytesOutputStream(bytesPool);
    }

    private final Registrar _registrar;
    private final PooledBytesOutputStream _output;
    private HttpRequest _request;
    private final AtomicReference<Detachable> _detachable =
            new AtomicReference<Detachable>(null);
    private JSONProvider jsonProvider = new FastJSONProvider();

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        safeDetachCurrentFlow();
        this._output.close();
    }

    /**
     * @throws Exception
     */
    private void safeDetachCurrentFlow() {
        final Detachable detachable = this._detachable.getAndSet(null);
        if (null != detachable) {
            try {
                detachable.detach();
            } catch (Exception e) {
                LOG.warn("exception when detach current flow, detail:{}",
                        ExceptionUtils.exception2detail(e));
            }
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            this._request = (HttpRequest) msg;

            if (is100ContinueExpected(this._request)) {
                send100Continue(ctx);
                return;
            }

        }

        if (msg instanceof HttpContent) {
            HttpContent httpContent = (HttpContent) msg;

            this.byteBuf2OutputStream(httpContent.content(), this._output);

            if (msg instanceof LastHttpContent) {

                final LastHttpContent trailer = (LastHttpContent) msg;
                this.byteBuf2OutputStream(trailer.content(), this._output);

                final Blob blob = this._output.drainToBlob();
                final String contentType = this._request.headers().get(HttpHeaders.Names.CONTENT_TYPE);

                //final byte[] bytes = decodeContentOf(blob, contentType);
                try {

                    invokeFlowOrResponseNoContent(ctx, this._request, blob, contentType, this._output);
                } finally {
                    if (null != blob) {
                        blob.release();
                    }
                }
            }
        }
    }

    private byte[] decodeContentOf(final Blob blob, final String contentType)
            throws Exception {
        if (null == blob || null == contentType) {
            return null;
        } else if ("application/cjson".equals(contentType)) {
            final InputStream is = blob.genInputStream();
            InflaterInputStream zis = null;
            final PooledBytesOutputStream decompressOut = new PooledBytesOutputStream(this._output.pool());

            try {
                zis = new InflaterInputStream(is, new Inflater());
                BlockUtils.inputStream2OutputStream(zis, decompressOut);
            } finally {
                try {
                    if (null != is) {
                        is.close();
                    }
                } catch (Throwable e) {
                }
                try {
                    if (null != zis) {
                        zis.close();
                    }
                } catch (Throwable e) {
                }
            }

            final Blob decompressBlob = decompressOut.drainToBlob();
            InputStream decompressIs = null;

            try {
                if (null != decompressBlob) {
                    decompressIs = decompressBlob.genInputStream();
                    if (null != decompressIs) {
                        final byte[] bytes = new byte[decompressIs.available()];
                        decompressIs.read(bytes);
                        return bytes;
                    }
                }
            } finally {
                if (null != decompressBlob) {
                    decompressBlob.release();
                }
                if (null != decompressIs) {
                    try {
                        decompressIs.close();
                    } catch (Throwable e) {
                    }
                }
            }
        } else if ("application/json".equals(contentType)) {
            InputStream is = null;
            try {
                is = blob.genInputStream();
                if (null != is) {
                    final byte[] bytes = new byte[is.available()];
                    is.read(bytes);
                    return bytes;
                }
            } finally {
                if (null != is) {
                    try {
                        is.close();
                    } catch (Throwable e) {
                    }
                }
            }
        }
        return null;
    }

    /**
     * @param ctx
     * @param request
     * @throws Exception
     */
    private void invokeFlowOrResponseNoContent(
            final ChannelHandlerContext ctx,
            final HttpRequest request,
            final Blob blob,
            final String contentType,
            final PooledBytesOutputStream output) throws Exception {
        final Pair<Object, String> flowAndEvent =
                this._registrar.buildFlowMatch(request.getMethod().name(), request.getUri(), request, blob, contentType, output);

        if (null == flowAndEvent) {
            // path not found
            writeAndFlushResponse(null, ctx);
//            ctx.flush();
            return;
        }

        final InterfaceSource flow = (InterfaceSource) flowAndEvent.getFirst();
        this._detachable.set(flow.queryInterfaceInstance(Detachable.class));

        try {
            ((OutputSource) flow).setOutputReactor(new OutputReactor() {

                @Override
                public void output(final Object representation) {
                    safeDetachCurrentFlow();
                    if (representation instanceof CustomHttpResponse) {
                        writeAndFlushHttpResponse((CustomHttpResponse) representation, ctx);
                    } else {
                        String responseJson = jsonProvider.toJSONString(representation);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("send resp:{}", responseJson);
                        }
                        writeAndFlushResponse(responseJson, ctx);
                    }
                }
            });
        } catch (Exception e) {
            LOG.warn("exception when call flow({})'s setOutputReactor, detail:{}",
                    flow, ExceptionUtils.exception2detail(e));
        }

        flow.queryInterfaceInstance(EventReceiver.class)
                .acceptEvent(flowAndEvent.getSecond());
    }

    private boolean writeAndFlushResponse(final String content, final ChannelHandlerContext ctx) {
        // Decide whether to close the connection or not.
        boolean keepAlive = isKeepAlive(_request);
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
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.writeAndFlush(response);
        }

        return keepAlive;
    }

    private boolean writeAndFlushHttpResponse(CustomHttpResponse httpResponse, final ChannelHandlerContext ctx) {
        // Decide whether to close the connection or not.
        boolean keepAlive = isKeepAlive(_request);
        // Build the response object.
        if (null != httpResponse) {
            try {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HTTP_1_1, (null != httpResponse.getContent() ? OK : NO_CONTENT),
                        (null != httpResponse.getContent() ? Unpooled.copiedBuffer(httpResponse.getContent()) : Unpooled.buffer(0)));
                Map<String, String> headers = httpResponse.getHeaders();
                for (String key : headers.keySet()) {
                    if (!key.equals(HttpHeaders.Values.KEEP_ALIVE)) {
                        response.headers().set(key, headers.get(key));
                    }
                }

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
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                } else {
                    ctx.writeAndFlush(response);
                }
            } catch (Exception e) {
                LOG.error("", e);
            }

        }
        return keepAlive;
    }

    private static void send100Continue(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, CONTINUE);
        ctx.writeAndFlush(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }

    /**
     * @param buf
     * @param os
     */
    private static long byteBuf2OutputStream(final ByteBuf buf, final PooledBytesOutputStream os) {
        final InputStream is = new ByteBufInputStream(buf);
        try {
            return BlockUtils.inputStream2OutputStream(is, os);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                // just ignore
            }
        }
    }

    public void setJsonProvider(JSONProvider jsonProvider) {
        this.jsonProvider = jsonProvider;
    }

    public void setUseJackson(boolean useJackson) {
        this.jsonProvider = useJackson ? new JacksonProvider() : new FastJSONProvider();
    }
}
