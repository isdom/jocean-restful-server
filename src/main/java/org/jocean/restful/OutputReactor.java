/**
 * 
 */
package org.jocean.restful;

import io.netty.handler.codec.http.FullHttpResponse;

/**
 * @author isdom
 *
 */
public interface OutputReactor {
    
    public void outputAsContentType(final Object representation, final String contentType);
    
    public void output(final Object representation);
    
    public void output(final Object representation, final String outerName);
    
    public void outputAsHttpResponse(final FullHttpResponse response);
}
