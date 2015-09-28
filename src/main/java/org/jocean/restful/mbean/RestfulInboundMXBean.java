package org.jocean.restful.mbean;

import org.jocean.http.server.mbean.InboundMXBean;

public interface RestfulInboundMXBean extends InboundMXBean {
    
    public String getCategory();
    
    public String getPathPattern();
    
    public int getPriority();
}
