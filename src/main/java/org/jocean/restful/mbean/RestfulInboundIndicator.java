package org.jocean.restful.mbean;

import io.netty.channel.ServerChannel;

import org.jocean.http.server.mbean.InboundIndicator;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.j2se.jmx.MBeanRegister;
import org.jocean.j2se.jmx.MBeanRegisterAware;

public class RestfulInboundIndicator extends InboundIndicator 
    implements RestfulInboundMXBean, ServerChannelAware, MBeanRegisterAware {

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
        super.setServerChannel(serverChannel);
        this._register.registerMBean("name=inbound,address=" + this.getBindIp()
                +",port=" + this.getPort(), this);
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
    
    private MBeanRegister _register;
    private String _pathPattern;
    private String _category;
    private int _priority;
}