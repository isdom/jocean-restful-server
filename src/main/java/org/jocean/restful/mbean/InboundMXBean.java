package org.jocean.restful.mbean;

public interface InboundMXBean {
    
    public String getHost();
    
    public String getHostIp();
    
    public String getBindIp();
    
    public int getPort();
    
    public String getCategory();
    
    public String getPathPattern();
    
    public int getPriority();
}
