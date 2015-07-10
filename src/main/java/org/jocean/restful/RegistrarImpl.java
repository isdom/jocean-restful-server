/**
 *
 */
package org.jocean.restful;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.EmptyByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.BeanParam;
import javax.ws.rs.CookieParam;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventEngine;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.event.api.internal.DefaultInvoker;
import org.jocean.event.api.internal.EventInvoker;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSource;
import org.jocean.idiom.Pair;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.io.ByteStreams;

/**
 * @author isdom
 */
public class RegistrarImpl implements  Registrar<RegistrarImpl> {

    private static final Logger LOG
            = LoggerFactory.getLogger(RegistrarImpl.class);

    public RegistrarImpl(final EventEngine source) {
        this._engine = source;
    }

    @Override
    public void setClasses(final Set<Class<?>> classes) {
        this._resources.clear();
        for (Class<?> cls : classes) {
            this.register(cls);
        }
    }

    @Override
    public RegistrarImpl register(final Class<?> cls) {

        final Class<?> flowCls = checkNotNull(cls);

        checkArgument(InterfaceSource.class.isAssignableFrom(flowCls),
                "flow class(%s) must implements InterfaceSource interface", flowCls);

        checkArgument(OutputSource.class.isAssignableFrom(flowCls),
                "flow class(%s) must implements OutputSource interface", flowCls);

        final String flowPath =
                checkNotNull(checkNotNull(flowCls.getAnnotation(Path.class),
                                "flow class(%s) must be annotation by Path", flowCls).value(),
                        "flow class(%s)'s Path must have value setted", flowCls
                );

        final Context flowCtx = new Context(flowCls);

        final int initMethodCount =
                addPathsByAnnotatedMethods(flowPath, flowCtx, GET.class)
                        + addPathsByAnnotatedMethods(flowPath, flowCtx, POST.class)
                        + addPathsByAnnotatedMethods(flowPath, flowCtx, PUT.class)
                        + addPathsByAnnotatedMethods(flowPath, flowCtx, DELETE.class);

        checkState((initMethodCount > 0),
                "can not find ANY init method annotation by GET/PUT/POST/DELETE for type(%s)", flowCls);

        if (LOG.isDebugEnabled()) {
            LOG.debug("register flowCtx({}) for path:{}", flowCtx, flowPath);
        }
        return this;
    }

    @Override
    public Pair<Object, String> buildFlowMatch(
            final HttpRequest request,
            final ByteBuf content,
            final Map<String, List<String>> formParameters
            ) throws Exception {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());

        final String rawPath = decoder.path();

        final Pair<Context, Map<String, String>> ctxAndParamValues =
                findContextByMethodAndPath(request.getMethod().name(), rawPath);

        if (null == ctxAndParamValues) {
            return null;
        }

        final Context ctx = ctxAndParamValues.getFirst();
        final Map<String, String> pathParamValues = ctxAndParamValues.getSecond();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Registrar: found flow class {} match path {}", ctx._cls, rawPath);
        }

        final Object flow = checkNotNull(this._beanHolder.getBean(ctx._cls),
                "can not build flow for type(%s)", ctx._cls);
        final Map<String, List<String>> queryValues = unionQueryValues(decoder.parameters(), formParameters);
        assignAllParams(ctx._field2params, flow, ctx._selfParams,
                pathParamValues, queryValues, request,
                decodeContent(content)
                );

        final EventInvoker invoker = DefaultInvoker.of(flow, ctx._init);

        final String event = invoker.getBindedEvent();

        this._engine.create(flow.toString(),
                new BizStep("INIT").handler(invoker).freeze(),
                flow);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Registrar: create flow({}) with init method({}), init event({})",
                    flow, ctx._init.getName(), event);
        }

        return Pair.of(flow, event);
    }

    private Map<String, List<String>> unionQueryValues(
            Map<String, List<String>> queryParameters,
            Map<String, List<String>> formParameters) {
        if (null==queryParameters || queryParameters.isEmpty()) {
            return formParameters;
        } else if (null==formParameters || formParameters.isEmpty()) {
            return queryParameters;
        } else {
            final ListMultimap<String, String> union = ArrayListMultimap.create();
            for (Map.Entry<String, List<String>> entry : queryParameters.entrySet()) {
                union.putAll(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, List<String>> entry : formParameters.entrySet()) {
                union.putAll(entry.getKey(), entry.getValue());
            }
            return Multimaps.asMap(union);
        }
    }

    private byte[] decodeContent(final ByteBuf content) {
        if (content instanceof EmptyByteBuf) {
            return null;
        }
        try {
            return ByteStreams.toByteArray(new ByteBufInputStream(content.slice()));
        } catch (IOException e) {
            LOG.warn("exception when decodeContent, detail:{}", 
                    ExceptionUtils.exception2detail(e));
            return null;
        }
    }
    
    /*
    private byte[] decodeContentOf(final Blob blob, final String contentType,boolean isRaw, final  PooledBytesOutputStream  output)
            throws Exception {
        if (null == blob || contentType == null) {
            return null;
        }else if(isRaw){
            InputStream is = null;
            try {
                is = blob.genInputStream();
                if (null != is) {
                    final byte[] bytes = new byte[is.available()];
                    is.read(bytes);
                    return bytes;
                }
            } finally {
                if (null != is) {
                    try {
                        is.close();
                    } catch (Throwable e) {
                    }
                }
            }
        }
        else if (contentType.startsWith("application/cjson")) {
            final InputStream is = blob.genInputStream();
            InflaterInputStream zis = null;
            final PooledBytesOutputStream decompressOut = new PooledBytesOutputStream(output.pool());

            try {
                zis = new InflaterInputStream(is, new Inflater());
                BlockUtils.inputStream2OutputStream(zis, decompressOut);
            } finally {
                try {
                    if (null != is) {
                        is.close();
                    }
                } catch (Throwable e) {
                }
                try {
                    if (null != zis) {
                        zis.close();
                    }
                } catch (Throwable e) {
                }
            }

            final Blob decompressBlob = decompressOut.drainToBlob();
            InputStream decompressIs = null;

            try {
                if (null != decompressBlob) {
                    decompressIs = decompressBlob.genInputStream();
                    if (null != decompressIs) {
                        final byte[] bytes = new byte[decompressIs.available()];
                        decompressIs.read(bytes);
                        return bytes;
                    }
                }
            } finally {
                if (null != decompressBlob) {
                    decompressBlob.release();
                }
                if (null != decompressIs) {
                    try {
                        decompressIs.close();
                    } catch (Throwable e) {
                    }
                }
            }
        } else if (contentType.startsWith("application/json")) {
            InputStream is = null;
            try {
                is = blob.genInputStream();
                if (null != is) {
                    final byte[] bytes = new byte[is.available()];
                    is.read(bytes);
                    return bytes;
                }
            } finally {
                if (null != is) {
                    try {
                        is.close();
                    } catch (Throwable e) {
                    }
                }
            }
        }
        return null;
    }
    */

    private static void assignAllParams(
            final Map<Field, Params> field2params,
            final Object obj,
            final Params params,
            final Map<String, String> pathParamValues,
            final Map<String, List<String>> queryParamValues,
            final HttpRequest request,
            final byte[] bytes) {
        if (null != params._pathParams && null != pathParamValues) {
            for (Field field : params._pathParams) {
                injectPathParamValue(pathParamValues.get(field.getAnnotation(PathParam.class).value()), 
                        obj, field);
            }
        }

        if (null != params._queryParams && null != queryParamValues) {
            for (Field field : params._queryParams) {
                injectParamValue(queryParamValues.get(field.getAnnotation(QueryParam.class).value()), 
                        obj, field);
            }
        }

        if (null != params._headerParams) {
            for (Field field : params._headerParams) {
                injectParamValue(request.headers().getAll(
                                field.getAnnotation(HeaderParam.class).value()), obj,
                        field
                );
            }
        }

        if (null != params._cookieParams) {
            final String rawCookie = request.headers().get(HttpHeaders.Names.COOKIE);
            if (null != rawCookie) {
                final Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(rawCookie);
                if (!cookies.isEmpty()) {
                    for (Field field : params._cookieParams) {
                        final Cookie nettyCookie = findCookieNamed(
                                cookies, field.getAnnotation(CookieParam.class).value());
                        if (null != nettyCookie) {
                            injectCookieParamValue(obj, field, nettyCookie);
                        }

                    }
                }
            }
        }

        if (null != params._beanParams) {
            for (Field beanField : params._beanParams) {
                try {
                    final Object bean = createObjectBy(bytes, beanField);
                    if (null != bean) {
                        beanField.set(obj, bean);
                        final Params beanParams = field2params.get(beanField);
                        if (null != beanParams) {
                            assignAllParams(field2params, bean, beanParams,
                                    pathParamValues, queryParamValues, request, bytes);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("exception when set bean value for field({}), detail:{}",
                            beanField, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    /**
     * @param bytes
     * @param beanField
     * @return
     */
    private static Object createObjectBy(final byte[] bytes, final Field beanField) {
        if (null != bytes) {
            if (LOG.isDebugEnabled()) {
                try {
                    LOG.debug("createObjectBy: {}", new String(bytes, "UTF-8"));
                } catch (UnsupportedEncodingException e) {
                }
            }
            return JSON.parseObject(bytes, beanField.getType());
        } else {
            try {
                return beanField.getType().newInstance();
            } catch (Throwable e) {
                LOG.warn("exception when create instance for type:{}, detail:{}",
                        beanField.getType(), ExceptionUtils.exception2detail(e));
                return null;
            }
        }
    }

    private Pair<Context, Map<String, String>> findContextByMethodAndPath(
            final String httpMethod, final String rawPath) {

        // try direct path match
        final Context ctx = this._resources.get(httpMethod + ":" + rawPath);
        if (null != ctx) {
            return Pair.of(ctx, null);
        } else {
            return matchPathWithParams(httpMethod, rawPath);
        }
    }

    private Pair<Context, Map<String, String>> matchPathWithParams(
            final String httpMethod, final String rawPath) {
        Collection<Pair<PathMatcher, Context>> matchers =
                this._pathmatchers.get(httpMethod);
        if (null != matchers) {
            for (Pair<PathMatcher, Context> matcher : matchers) {
                final Map<String, String> paramValues = matcher.getFirst().match(rawPath);
                if (null != paramValues) {
                    return Pair.of(matcher.getSecond(), paramValues);
                }
            }
        }
        return null;
    }

    private static void injectCookieParamValue(
            final Object flow,
            final Field field,
            final Cookie nettyCookie) {
        if (field.getType().equals(javax.ws.rs.core.Cookie.class)) {
            try {
                field.set(flow, new javax.ws.rs.core.Cookie(nettyCookie.name(),
                        nettyCookie.value(), nettyCookie.path(),
                        nettyCookie.domain(), 0));
            } catch (Exception e) {
                LOG.warn("exception when set flow({}).{} CookieParam({}), detail:{} ",
                        flow, field.getName(), nettyCookie, ExceptionUtils.exception2detail(e));
            }
        }
    }

    private static Cookie findCookieNamed(final Iterable<Cookie> cookies, final String name) {
        for (Cookie cookie : cookies) {
            if (cookie.name().equals(name)) {
                return cookie;
            }
        }
        return null;
    }

    private static void injectPathParamValue(
            final String value,
            final Object obj,
            final Field field) {
        injectValueToField(value, obj, field);
    }

    private static void injectParamValue(
            final List<String> values,
            final Object obj,
            final Field field) {
        if (null != values && values.size() > 0) {
            injectValueToField(values.get(0), obj, field);
        }
    }

    /**
     * @param value
     * @param obj
     * @param field
     */
    private static void injectValueToField(
            final String value,
            final Object obj,
            final Field field) {
        if (null != value) {
            try {
                // just check String field
                if (field.getType().equals(String.class)) {
                    field.set(obj, value);
                } else {
                    final PropertyEditor editor = PropertyEditorManager.findEditor(field.getType());
                    if (null != editor) {
                        editor.setAsText(value);
                        field.set(obj, editor.getValue());
                    }
                }
            } catch (Exception e) {
                LOG.warn("exception when set obj({}).{} with value({}), detail:{} ",
                        obj, field.getName(), value, ExceptionUtils.exception2detail(e));
            }
        }
    }

    private int addPathsByAnnotatedMethods(
            final String flowPath,
            final Context flowCtx,
            final Class<? extends Annotation> httpMethodAnnotation) {
        final Method[] initMethods =
                ReflectUtils.getAnnotationMethodsOf(flowCtx._cls, httpMethodAnnotation);

        if (initMethods.length > 0) {

            for (Method init : initMethods) {
                checkNotNull(init.getAnnotation(OnEvent.class),
                        "flow class(%s)'s method(%s) must be annotation with OnEvent", flowCtx._cls, init.getName());

                final String methodPath = genMethodPathOf(flowPath, init);
                registerPathOfContext(httpMethodAnnotation, methodPath,
                        new Context(flowCtx, init));
            }
        }

        return initMethods.length;
    }

    private void registerPathOfContext(
            final Class<? extends Annotation> httpMethodAnnotation,
            final String methodPath,
            final Context context) {
        final String httpMethod = checkNotNull(httpMethodAnnotation.getAnnotation(HttpMethod.class),
                "(%s) must annotated by HttpMethod", httpMethodAnnotation).value();

        final PathMatcher pathMatcher = PathMatcher.create(methodPath);
        if (null == pathMatcher) {
            //  Path without parameters
            this._resources.put(httpMethod + ":" + methodPath, context);

            if (LOG.isDebugEnabled()) {
                LOG.debug("register httpMethod {} for Path {} with context {}",
                        httpMethod, methodPath, context);
            }
        } else {
            // Path !WITH! parameters
            this._pathmatchers.put(httpMethod, Pair.of(pathMatcher, context));
            if (LOG.isDebugEnabled()) {
                LOG.debug("register httpMethod {} for !Parametered! Path {} with matcher {} & context {}",
                        httpMethod, methodPath, pathMatcher, context);
            }
        }
    }

    private String genMethodPathOf(final String flowPath, final Method method) {
        final Path methodPath = method.getAnnotation(Path.class);

        if (null != methodPath) {
            return flowPath + methodPath.value();
        } else {
            return flowPath;
        }
    }

    public void setBeanHolder(final BeanHolder beanHolder) throws BeansException {
        this._beanHolder = beanHolder;
    }

    private static final class Params {
        private final Field[] _pathParams;
        private final Field[] _queryParams;
        private final Field[] _headerParams;
        private final Field[] _cookieParams;
        private final Field[] _beanParams;

        Params(final Field[] pathParams,
               final Field[] queryParams, final Field[] headerParams,
               final Field[] cookieParams, final Field[] beanParams) {
            this._pathParams = pathParams;
            this._queryParams = queryParams;
            this._headerParams = headerParams;
            this._cookieParams = cookieParams;
            this._beanParams = beanParams;
        }

        @Override
        public String toString() {
            return "Params [_pathParams=" + Arrays.toString(_pathParams)
                    + ", _queryParams=" + Arrays.toString(_queryParams)
                    + ", _headerParams=" + Arrays.toString(_headerParams)
                    + ", _cookieParams=" + Arrays.toString(_cookieParams)
                    + ", _beanParams=" + Arrays.toString(_beanParams) + "]";
        }
    }

    private static void fetchAllParams(final Field owner, final Class<?> cls, final Map<Field, Params> field2params) {
        final Field[] beanFields = ReflectUtils.getAnnotationFieldsOf(cls, BeanParam.class);
        field2params.put(owner,
                new Params(
                        ReflectUtils.getAnnotationFieldsOf(cls, PathParam.class),
                        ReflectUtils.getAnnotationFieldsOf(cls, QueryParam.class),
                        ReflectUtils.getAnnotationFieldsOf(cls, HeaderParam.class),
                        ReflectUtils.getAnnotationFieldsOf(cls, CookieParam.class),
                        beanFields)
        );

        for (Field field : beanFields) {
            fetchAllParams(field, field.getType(), field2params);
        }
    }

    private static class Context {

        Context(final Context ctx,
                final Method init
        ) {
            this._cls = ctx._cls;
            this._init = init;
            this._selfParams = ctx._selfParams;
            this._field2params = new HashMap<Field, Params>(ctx._field2params);
        }

        Context(final Class<?> cls) {
            this._cls = cls;
            this._init = null;
            final Field[] beanFields = ReflectUtils.getAnnotationFieldsOf(cls, BeanParam.class);
            this._selfParams = new Params(
                    ReflectUtils.getAnnotationFieldsOf(cls, PathParam.class),
                    ReflectUtils.getAnnotationFieldsOf(cls, QueryParam.class),
                    ReflectUtils.getAnnotationFieldsOf(cls, HeaderParam.class),
                    ReflectUtils.getAnnotationFieldsOf(cls, CookieParam.class),
                    beanFields);
            this._field2params = new HashMap<Field, Params>();
            for (Field field : beanFields) {
                fetchAllParams(field, field.getType(), this._field2params);
            }
        }

        private final Class<?> _cls;
        private final Method _init;
        private final Params _selfParams;
        private final Map<Field, Params> _field2params;

        @Override
        public String toString() {
            return "Context [_cls=" + _cls + ", _init=" + _init
                    + ", _selfParams=" + _selfParams + ", _field2params="
                    + _field2params + "]";
        }
    }

    private final Map<String, Context> _resources =
            new HashMap<String, Context>();

    private final Multimap<String, Pair<PathMatcher, Context>> _pathmatchers = ArrayListMultimap.create();

    private BeanHolder _beanHolder;
    private final EventEngine _engine;
}
