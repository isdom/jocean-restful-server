/**
 *
 */
package org.jocean.restful;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.ws.rs.BeanParam;
import javax.ws.rs.CookieParam;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EndReasonAware;
import org.jocean.event.api.EventEngine;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.event.api.internal.DefaultInvoker;
import org.jocean.event.api.internal.EventInvoker;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSource;
import org.jocean.idiom.Pair;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.Regexs;
import org.jocean.idiom.StopWatch;
import org.jocean.j2se.jmx.MBeanRegister;
import org.jocean.j2se.jmx.MBeanRegisterAware;
import org.jocean.j2se.spring.SpringBeanHolder;
import org.jocean.j2se.stats.FlowStats;
import org.jocean.j2se.unit.UnitAgent;
import org.jocean.j2se.unit.UnitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

import com.alibaba.fastjson.JSON;
import com.google.common.base.Charsets;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.EmptyByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

/**
 * @author isdom
 */
public class RegistrarImpl implements Registrar<RegistrarImpl>, MBeanRegisterAware {

    private static final Logger LOG
            = LoggerFactory.getLogger(RegistrarImpl.class);

    public RegistrarImpl(final EventEngine engine) {
        this._engine = engine;
    }

    public void start() {
        final ConfigurableListableBeanFactory[] factorys = this._beanHolder.allBeanFactory();
        for (ConfigurableListableBeanFactory factory : factorys) {
            scanAndRegisterFlow(factory);
        }
        if (this._beanHolder instanceof UnitAgent) {
            final UnitAgent agent = (UnitAgent)this._beanHolder;
            agent.addUnitListener(_unitListener);
        }
    }
    
    public void stop() {
        if (this._beanHolder instanceof UnitAgent) {
            final UnitAgent agent = (UnitAgent)this._beanHolder;
            agent.removeUnitListener(this._unitListener);
        }
        this._flowCtxs.clear();
        this._pathMatchers.clear();
    }
    
    public void setBeanHolder(final SpringBeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }

    private void scanAndRegisterFlow(final ConfigurableListableBeanFactory factory) {
        for ( String name : factory.getBeanDefinitionNames() ) {
            final BeanDefinition def = factory.getBeanDefinition(name);
            if (null!=def && null != def.getBeanClassName()) {
                try {
                    final Class<?> cls = Class.forName(def.getBeanClassName());
                    if (AbstractFlow.class.isAssignableFrom(cls)
                       && OutputSource.class.isAssignableFrom(cls)) {
                        register(cls);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when scanAndRegisterFlow, detail: {}", 
                            ExceptionUtils.exception2detail(e));
                }
            } else {
                LOG.warn("scanAndRegisterFlow: bean named {} 's definition is empty.", name);
            }
        }
    }

    private void unregisterAllFlow(final ConfigurableListableBeanFactory factory) {
        for ( String name : factory.getBeanDefinitionNames() ) {
            final BeanDefinition def = factory.getBeanDefinition(name);
            if (null!=def && null != def.getBeanClassName()) {
                try {
                    final Class<?> cls = Class.forName(def.getBeanClassName());
                    if (AbstractFlow.class.isAssignableFrom(cls)) {
                        unregister(cls);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when unregisterAllFlow, detail: {}", 
                            ExceptionUtils.exception2detail(e));
                }
            } else {
                LOG.warn("unregisterAllFlow: bean named {} 's definition is empty.", name);
            }
        }
    }

    @Override
    public void setClasses(final Set<Class<?>> classes) {
        this._flowCtxs.clear();
        this._pathMatchers.clear();
        for (Class<?> cls : classes) {
            this.register(cls);
        }
    }

    public void setPathPattern(final String pathPattern) {
        this._pathPattern = Regexs.safeCompilePattern(pathPattern);;
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

        if (!Regexs.isMatched(this._pathPattern, flowPath)) {
            LOG.info("flow {} 's path {} !NOT! match path pattern {}, just ignore",
                    flowCls, flowPath, this._pathPattern);
            return this;
        }
        
        final FlowContext flowCtx = new FlowContext(flowCls);

        final int initMethodCount =
            addPathsByAnnotatedMethods(flowPath, flowCtx, GET.class)
            + addPathsByAnnotatedMethods(flowPath, flowCtx, POST.class)
            + addPathsByAnnotatedMethods(flowPath, flowCtx, PUT.class)
            + addPathsByAnnotatedMethods(flowPath, flowCtx, DELETE.class)
            + addPathsByAnnotatedMethods(flowPath, flowCtx, OPTIONS.class)
            + addPathsByAnnotatedMethods(flowPath, flowCtx, HEAD.class);

        checkState((initMethodCount > 0),
                "can not find ANY init method annotation by GET/PUT/POST/DELETE/OPTIONS/HEAD for type(%s)", flowCls);

        if (LOG.isDebugEnabled()) {
            LOG.debug("register flowCtx({}) for path:{}", flowCtx, flowPath);
        }
        return this;
    }

    public RegistrarImpl unregister(final Class<?> cls) {
        LOG.info("unregister {}'s entry.", cls);
        {
            final Iterator<Map.Entry<String, FlowContext>> itr = 
                    this._flowCtxs.entrySet().iterator();
            while ( itr.hasNext()) {
                final Map.Entry<String, FlowContext> entry = itr.next();
                if (entry.getValue()._cls.equals(cls)) {
                    itr.remove();
                    LOG.info("remove {} from resources.", entry.getKey());
                }
            }
        }
        
        {
            Iterator<Map.Entry<String, Pair<PathMatcher, FlowContext>>> itr = 
                    this._pathMatchers.entries().iterator();
            while ( itr.hasNext()) {
                final Map.Entry<String, Pair<PathMatcher, FlowContext>> entry = itr.next();
                if (entry.getValue().second._cls.equals(cls)) {
                    itr.remove();
                    LOG.info("remove {} from _pathMatchers.", entry.getKey());
                }
            }
        }
        return this;
    }
    
    @Override
    public Pair<Object, String> buildFlowMatch(
            final HttpRequest request,
            final String contentType,
            final ByteBuf content,
            final Map<String, List<String>> formParameters
            ) throws Exception {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());

        final String rawPath = getRawPath(decoder.path());

        final Pair<FlowContext, Map<String, String>> ctxAndParamValues =
                findContextByMethodAndPath(request.getMethod().name(), rawPath);

        if (null == ctxAndParamValues) {
            return null;
        }

        final FlowContext ctx = ctxAndParamValues.getFirst();
        final Map<String, String> pathParamValues = ctxAndParamValues.getSecond();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Registrar: found flow class {} match path {}", ctx._cls, rawPath);
        }

        final Object flow = checkNotNull(this._beanHolder.getBean(ctx._cls),
                "can not build flow for type(%s)", ctx._cls);
        final Map<String, List<String>> queryValues = unionQueryValues(decoder.parameters(), formParameters);
        assignAllParams(
                ctx._field2params, 
                flow, 
                ctx._selfParams,
                pathParamValues, 
                queryValues, 
                request,
                contentType,
                decodeContent(content)
                );

        final EventInvoker invoker = DefaultInvoker.of(flow, ctx._init);

        final String event = invoker.getBindedEvent();

        final StopWatch clock = new StopWatch();
        final AtomicReference<String> endReasonRef = new AtomicReference<>("default");
        this._engine.create(flow.toString(),
                new BizStep("INIT").handler(invoker).freeze(),
                flow,
                new EndReasonAware() {
                    @Override
                    public void setEndReason(final Object endreason) {
                        endReasonRef.set(endreason.toString());
                    }},
                new FlowLifecycleListener() {
                    @Override
                    public void afterEventReceiverCreated(final EventReceiver receiver)
                            throws Exception {
                    }

                    @Override
                    public void afterFlowDestroy() throws Exception {
                        final int count = _stats.incExecutedCount(ctx._cls);
                        _stats.recordExecutedInterval(ctx._cls, endReasonRef.get(), clock.stopAndRestart());
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("{}'s afterFlowDestroy, so record biz count: {}", 
                                    flow, count);
                        }
                    }});

        if (LOG.isDebugEnabled()) {
            LOG.debug("Registrar: create flow({}) with init method({}), init event({})",
                    flow, ctx._init.getName(), event);
        }

        return Pair.of(flow, event);
    }

    private String getRawPath(final String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            //  http://wangsz.xicp.net:10900/call/zmccCallEndNotify
            final int schemeIdx = path.indexOf("://");
            final String pathWithoutScheme = path.substring(schemeIdx + 3);
            final int rawPathIdx = pathWithoutScheme.indexOf('/');
            return (rawPathIdx > 0) ? pathWithoutScheme.substring(rawPathIdx) : "/";
        } else {
            return path;
        }
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
    
    private static void assignAllParams(
            final Map<Field, Params> field2params,
            final Object obj,
            final Params params,
            final Map<String, String> pathParamValues,
            final Map<String, List<String>> queryParamValues,
            final HttpRequest request,
            final String contentType, 
            final byte[] bytes) {
        if (null != params._pathParams && null != pathParamValues) {
            for (Field field : params._pathParams) {
                injectPathParamValue(pathParamValues.get(field.getAnnotation(PathParam.class).value()), 
                        obj, field);
            }
        }

        if (null != params._queryParams ) {
            for (Field field : params._queryParams) {
                final String key = field.getAnnotation(QueryParam.class).value();
                if (!"".equals(key) && null != queryParamValues) {
                    injectParamValue(queryParamValues.get(key), obj, field);
                }
                if ("".equals(key)) {
                    injectValueToField(rawQuery(request.getUri()), obj, field);
                }
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
                    final Object bean = createObjectBy(
                            contentType, 
                            bytes, 
                            beanField);
                    if (null != bean) {
                        beanField.set(obj, bean);
                        final Params beanParams = field2params.get(beanField);
                        if (null != beanParams) {
                            assignAllParams(field2params, 
                                    bean, 
                                    beanParams,
                                    pathParamValues, 
                                    queryParamValues, 
                                    request, 
                                    contentType,
                                    bytes);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("exception when set bean value for field({}), detail:{}",
                            beanField, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    private static String rawQuery(final String uri) {
        final int pos = uri.indexOf('?');
        if (-1 != pos) {
            return uri.substring(pos+1);
        } else {
            return null;
        }
    }

    /**
     * @param string 
     * @param bytes
     * @param beanField
     * @return
     */
    private static Object createObjectBy(final String contentType, final byte[] bytes, final Field beanField) {
        if (null != bytes) {
            if (beanField.getType().equals(byte[].class)) {
                if (LOG.isDebugEnabled()) {
                    try {
                        LOG.debug("assign byte array with: {}", new String(bytes, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        LOG.debug("assign byte array with: {}", Arrays.toString(bytes));
                    }
                }
                return bytes;
            } else if ( null != contentType
                    && contentType.startsWith("application/json")) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("createObjectBy: {}", new String(bytes, Charsets.UTF_8));
                }
                return JSON.parseObject(bytes, beanField.getType());
            }
        } 
        try {
            return beanField.getType().newInstance();
        } catch (Throwable e) {
            LOG.warn("exception when create instance for type:{}, detail:{}",
                    beanField.getType(), ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    private Pair<FlowContext, Map<String, String>> findContextByMethodAndPath(
            final String httpMethod, final String rawPath) {

        // try direct path match
        final FlowContext ctx = this._flowCtxs.get(httpMethod + ":" + rawPath);
        if (null != ctx) {
            return Pair.of(ctx, null);
        } else {
            return matchPathWithParams(httpMethod, rawPath);
        }
    }

    private Pair<FlowContext, Map<String, String>> matchPathWithParams(
            final String httpMethod, final String rawPath) {
        Collection<Pair<PathMatcher, FlowContext>> matchers =
                this._pathMatchers.get(httpMethod);
        if (null != matchers) {
            for (Pair<PathMatcher, FlowContext> matcher : matchers) {
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
            final FlowContext flowCtx,
            final Class<? extends Annotation> httpMethodAnnotation) {
        final Method[] initMethods =
                ReflectUtils.getAnnotationMethodsOf(flowCtx._cls, httpMethodAnnotation);

        if (initMethods.length > 0) {

            for (Method init : initMethods) {
                checkNotNull(init.getAnnotation(OnEvent.class),
                        "flow class(%s)'s method(%s) must be annotation with OnEvent", flowCtx._cls, init.getName());

                final String methodPath = genMethodPathOf(flowPath, init);
                registerPathOfContext(httpMethodAnnotation, methodPath,
                        new FlowContext(flowCtx, init));
            }
        }

        return initMethods.length;
    }

    @SuppressWarnings("unchecked")
    private void registerPathOfContext(
            final Class<? extends Annotation> httpMethodAnnotation,
            final String methodPath,
            final FlowContext context) {
        final String httpMethod = checkNotNull(httpMethodAnnotation.getAnnotation(HttpMethod.class),
                "(%s) must annotated by HttpMethod", httpMethodAnnotation).value();

        this._stats.addFlows(methodPath, httpMethod, (Class<Object>)context._cls);
        final PathMatcher pathMatcher = PathMatcher.create(methodPath);
        if (null == pathMatcher) {
            //  Path without parameters
            this._flowCtxs.put(httpMethod + ":" + methodPath, context);

            if (LOG.isDebugEnabled()) {
                LOG.debug("register httpMethod {} for Path {} with context {}",
                        httpMethod, methodPath, context);
            }
        } else {
            // Path !WITH! parameters
            this._pathMatchers.put(httpMethod, Pair.of(pathMatcher, context));
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

    private static class FlowContext {

        FlowContext(final FlowContext ctx,
                final Method init
        ) {
            this._cls = ctx._cls;
            this._init = init;
            this._selfParams = ctx._selfParams;
            this._field2params = new HashMap<Field, Params>(ctx._field2params);
        }

        FlowContext(final Class<?> cls) {
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
            return "FlowContext [_cls=" + _cls + ", _init=" + _init
                    + ", _selfParams=" + _selfParams + ", _field2params="
                    + _field2params + "]";
        }
    }

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
//        this._register = register;
    }
    
    @Inject
    private FlowStats _stats;
    
    private final Map<String, FlowContext> _flowCtxs =
            new HashMap<String, FlowContext>();

    private final Multimap<String, Pair<PathMatcher, FlowContext>> _pathMatchers = 
            ArrayListMultimap.create();

    private final UnitListener _unitListener = new UnitListener() {
        @Override
        public void postUnitCreated(final String unitPath, 
                final ConfigurableApplicationContext appctx) {
            scanAndRegisterFlow(appctx.getBeanFactory());
        }
        @Override
        public void beforeUnitClosed(final String unitPath,
                final ConfigurableApplicationContext appctx) {
            unregisterAllFlow(appctx.getBeanFactory());
        }
    };
    
    private SpringBeanHolder _beanHolder;
    private final EventEngine _engine;
    private Pattern _pathPattern;
//    private MBeanRegister _register;
}
