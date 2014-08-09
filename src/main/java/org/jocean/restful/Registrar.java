/**
 *
 */
package org.jocean.restful;

import io.netty.handler.codec.http.HttpRequest;

import java.util.Set;

import org.jocean.idiom.Pair;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.block.PooledBytesOutputStream;

/**
 * @author isdom
 */
public interface Registrar<REG extends Registrar<?>> {

    public void setClasses(final Set<Class<?>> classes);
    
    public REG register(final Class<?> cls);

    public Pair<Object, String> buildFlowMatch(
            final String httpMethod,
            final String uri,
            final HttpRequest request,
            final Blob blob,
            final String contentType,
            final PooledBytesOutputStream output) throws Exception;
}
