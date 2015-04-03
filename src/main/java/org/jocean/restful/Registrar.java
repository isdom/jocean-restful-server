/**
 *
 */
package org.jocean.restful;

import java.util.Set;

import org.jocean.http.HttpRequestWrapper;
import org.jocean.idiom.Pair;

/**
 * @author isdom
 */
public interface Registrar<REG extends Registrar<?>> {

    public void setClasses(final Set<Class<?>> classes);
    
    public REG register(final Class<?> cls);

    public Pair<Object, String> buildFlowMatch(
            final String httpMethod,
            final String uri,
            final HttpRequestWrapper request) throws Exception;
}
