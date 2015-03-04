/**
 * 
 */
package org.jocean.restful.http;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;

import org.jocean.idiom.pool.BytesPool;
import org.jocean.restful.Registrar;

/**
 * @author isdom
 *
 */
public class RestfulHttpServerInitializer extends ChannelInitializer<SocketChannel> {
    
    public RestfulHttpServerInitializer(final Registrar registrar, final boolean enableSSL, final BytesPool bytesPool) {
        this._registrar = registrar;
        this._enableSSL = enableSSL;
        this._bytesPool = bytesPool;
    }
    
    @Override
    public void initChannel(final SocketChannel ch) throws Exception {
        // Create a default pipeline implementation.
        final ChannelPipeline p = ch.pipeline();

        if ( this._enableSSL ) {
        // Uncomment the following line if you want HTTPS
//            final SSLEngine engine = J2SESslContextFactory.getServerContext().createSSLEngine();
//            engine.setUseClientMode(false);
//            p.addLast("ssl", new SslHandler(engine));
        }

        p.addLast("decoder", new HttpRequestDecoder());
        // Uncomment the following line if you don't want to handle HttpChunks.
        //p.addLast("aggregator", new HttpObjectAggregator(1048576));
        p.addLast("encoder", new HttpResponseEncoder());
        // Remove the following line if you don't want automatic content compression.
        //p.addLast("deflater", new HttpContentCompressor());
        p.addLast("handler", new RestfulHttpServerHandler( this._registrar, this._bytesPool));
    }
    
    private final Registrar _registrar;
    private final boolean _enableSSL;
    private final BytesPool _bytesPool;
}