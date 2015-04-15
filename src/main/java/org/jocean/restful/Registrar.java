/**
 *
 */
package org.jocean.restful;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jocean.idiom.Pair;

/**
 * @author isdom
 */
public interface Registrar<REG extends Registrar<?>> {

    public void setClasses(final Set<Class<?>> classes);
    
    public REG register(final Class<?> cls);

    public Pair<Object, String> buildFlowMatch(
            final HttpRequest request,
            final ByteBuf content, 
            final Map<String, List<String>> formParameters) throws Exception;
}
