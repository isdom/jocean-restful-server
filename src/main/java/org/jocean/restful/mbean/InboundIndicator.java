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

    @Override
    public String getHost() {
        return _HOSTNAME;
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
    public int getPriority() {
        return this._priority;
    }
    
    @Override
    public void setServerChannel(final ServerChannel serverChannel) {
        this._port = ((InetSocketAddress)serverChannel.localAddress()).getPort();
        this._register.registerMBean(this._mbeanName, this);
    }

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        this._register = register;
    }
    
    public void setMbeanName(final String mbeanName) {
        this._mbeanName = mbeanName;
    }
    
    public void setPathPattern(final String pathPattern) {
        this._pathPattern = pathPattern;
    }

    public void setPriority(final int priority) {
        this._priority = priority;
    }
    
    private static final String _HOSTNAME;
    private volatile int _port;
    
    static {
        String hostname = "unknown";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
        }
        _HOSTNAME = hostname;
    }
    
    private MBeanRegister _register;
    private String _mbeanName;
    private String _pathPattern;
    private int _priority;
}
