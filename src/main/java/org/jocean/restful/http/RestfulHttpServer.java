/**
 * 
 */
package org.jocean.restful.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import org.jocean.idiom.pool.BytesPool;
import org.jocean.restful.Registrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class RestfulHttpServer {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(RestfulHttpServer.class);

    private static final int MAX_RETRY = 20;
    private static final long RETRY_TIMEOUT = 30 * 1000; // 30s
    
    private Channel _acceptorChannel;
    
    private int _acceptPort = 65000;
    private String _acceptIp = "0.0.0.0";
    private boolean _enableSSL;
    
    private final BytesPool _bytesPool;

    private final Registrar<?> _registrar;
    
    private final ServerBootstrap _bootstrap = new ServerBootstrap();
    private final EventLoopGroup _acceptorGroup = new NioEventLoopGroup();
    private final EventLoopGroup _clientGroup = new NioEventLoopGroup();
    
    public RestfulHttpServer(final BytesPool bytesPool, final Registrar<?> registrar) {
        this._bytesPool = bytesPool;
        this._registrar = registrar;
    }
    
    public void start() throws Exception {
        this._bootstrap.group(this._acceptorGroup,this._clientGroup)
                .channel(NioServerSocketChannel.class)
                .localAddress(this._acceptIp,this._acceptPort)
                /*
                 * SO_BACKLOG
                 * Creates a server socket and binds it to the specified local port number, with the specified backlog.
                 * The maximum queue length for incoming connection indications (a request to connect) is set to the backlog parameter. 
                 * If a connection indication arrives when the queue is full, the connection is refused. 
                 */
                .option(ChannelOption.SO_BACKLOG, 10240)
                /*
                 * SO_REUSEADDR
                 * Allows socket to bind to an address and port already in use. The SO_EXCLUSIVEADDRUSE option can prevent this. 
                 * Also, if two sockets are bound to the same port the behavior is undefined as to which port will receive packets.
                 */
                .option(ChannelOption.SO_REUSEADDR, true)
                /*
                 * SO_RCVBUF
                 * The total per-socket buffer space reserved for receives. 
                 * This is unrelated to SO_MAX_MSG_SIZE and does not necessarily correspond to the size of the TCP receive window. 
                 */
                //.option(ChannelOption.SO_RCVBUF, 8 * 1024)
                /*
                 * SO_SNDBUF
                 * The total per-socket buffer space reserved for sends. 
                 * This is unrelated to SO_MAX_MSG_SIZE and does not necessarily correspond to the size of a TCP send window.
                 */
                //.option(ChannelOption.SO_SNDBUF, 8 * 1024)
                /*
                 * SO_KEEPALIVE
                 * Enables keep-alive for a socket connection. 
                 * Valid only for protocols that support the notion of keep-alive (connection-oriented protocols). 
                 * For TCP, the default keep-alive timeout is 2 hours and the keep-alive interval is 1 second. 
                 * The default number of keep-alive probes varies based on the version of Windows. 
                 */
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                /*
                 * TCP_NODELAY
                 * Enables or disables the Nagle algorithm for TCP sockets. This option is disabled (set to FALSE) by default.
                 */
                .childOption(ChannelOption.TCP_NODELAY, true)
                /*
                 * SO_LINGER
                 * Indicates the state of the linger structure associated with a socket. 
                 * If the l_onoff member of the linger structure is nonzero, 
                 * a socket remains open for a specified amount of time 
                 * after a closesocket function call to enable queued data to be sent. 
                 * The amount of time, in seconds, to remain open 
                 * is specified in the l_linger member of the linger structure. 
                 * This option is only valid for reliable, connection-oriented protocols.
                 */
                .childOption(ChannelOption.SO_LINGER, -1);
        
        this._bootstrap.childHandler(new RestfulHttpServerInitializer(this._registrar, this._enableSSL, this._bytesPool));

        int retryCount = 0;
        boolean binded = false;

        do {
            try {
                this._acceptorChannel = this._bootstrap.bind().channel();
                binded = true;
            } catch (final ChannelException e) {
                LOG.warn("start failed : {}, and retry...", e);

                //  对绑定异常再次进行尝试
                retryCount++;
                if (retryCount >= MAX_RETRY) {
                    //  超过最大尝试次数
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_TIMEOUT);
                } catch (InterruptedException ignored) {
                }
            }
        } while (!binded);
        
    }

    public void stop() {
        if (null != this._acceptorChannel) {
            this._acceptorChannel.disconnect();
            this._acceptorChannel = null;
        }
        this._acceptorGroup.shutdownGracefully();
        this._clientGroup.shutdownGracefully();
    }
    
    public void setAcceptPort(int acceptPort) {
        this._acceptPort = acceptPort;
    }

    public void setAcceptIp(String acceptIp) {
        this._acceptIp = acceptIp;
    }
    
    public void setSslEnabled(final boolean enabled) {
        this._enableSSL = enabled;
    }
}
