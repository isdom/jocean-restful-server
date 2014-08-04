package org.jocean.restful;


import java.util.Map;

/**
 * Created by zhiqiangjiang on 14-6-24.
 */
public interface RawAware {

    public void setRaws(byte[] raws);

    public void setHttpHeaders(Map<String,String> httpHeaders);

    public void setUrl(String url);
}
