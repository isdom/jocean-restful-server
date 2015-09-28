package org.jocean.restful.mbean;

import io.netty.channel.ServerChannel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.jocean.http.Feature;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.j2se.jmx.MBeanRegister;
import org.jocean.j2se.jmx.MBeanRegisterAware;

public class InboundIndicator extends Feature.AbstractFeature0 
    implements InboundMXBean, ServerChannelAware, MBeanRegisterAware {

    public InboundIndicator() {
        String hostname = "unknown";
        String hostip = "0.0.0.0";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
            hostip = InetAddress.getByName(hostname).toString();
        } catch (UnknownHostException e) {
        }
        this._hostname = hostname;
        this._hostip = hostip;
    }
    
    @Override
    public String getHost() {
        return _hostname;
    }

    @Override
    public String getHostIp() {
        return this._hostip;
    }
    
    @Override
    public String getBindIp() {
        return this._bindip;
    }
    
    @Override
    public int getPort() {
        return this._port;
    }
    
    @Override
    public String getPathPattern() {
        return this._pathPattern;
    }

    @Override
    public String getCategory() {
        return this._category;
    }
    
    @Override
    public int getPriority() {
        return this._priority;
    }
    
    @Override
    public void setServerChannel(final ServerChannel serverChannel) {
        if (serverChannel.localAddress() instanceof InetSocketAddress) {
            final InetSocketAddress addr = (InetSocketAddress)serverChannel.localAddress();
            this._port = addr.getPort();
            this._bindip = null != addr.getAddress()
                    ? addr.getAddress().toString()
                    : "0.0.0.0";
        }
        this._register.registerMBean("name=inbound,address=" + this._bindip
                +",port=" + this._port, this);
    }

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        this._register = register;
    }
    
    public void setPathPattern(final String pathPattern) {
        this._pathPattern = pathPattern;
    }

    public void setCategory(final String category) {
        this._category = category;
    }
    
    public void setPriority(final int priority) {
        this._priority = priority;
    }
    
    private final String _hostname;
    private final String _hostip;
    private volatile String _bindip;
    private volatile int _port = 0;
    private MBeanRegister _register;
    private String _pathPattern;
    private String _category;
    private int _priority;
}
