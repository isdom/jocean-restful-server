package org.jocean.restful.mbean;

public interface InboundMXBean {
    
    public String getHost();
    
    public int getPort();
    
    public String getPathPattern();
    
    public int getPriority();
}
