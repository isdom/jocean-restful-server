package org.jocean.restful.http;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by zhiqiangjiang on 14-6-24.
 */
public class CustomHttpResponse {

    private String contentType;

    private byte[] content;

    private Map<String,String> headers = new HashMap<String, String>();

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }
}
