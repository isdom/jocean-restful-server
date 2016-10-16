package org.jocean.restful.trade;

import java.lang.reflect.Field;

import javax.ws.rs.HeaderParam;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpResponse;
import rx.functions.Action2;

public class ResponseProcessor implements Action2<Object, HttpResponse> {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(ResponseProcessor.class);
    
    public ResponseProcessor(final Class<?> clsResponse) {
        this._headerFields = 
            ReflectUtils.getAnnotationFieldsOf(clsResponse, HeaderParam.class);
    }
    
    @Override
    public void call(final Object respBean, final HttpResponse resp) {
        if (0 != this._headerFields.length) {
            for ( Field field : this._headerFields ) {
                try {
                    final Object value = field.get(respBean);
                    if ( null != value ) {
                        final String headername = 
                            field.getAnnotation(HeaderParam.class).value();
                        resp.headers().set(headername, value);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when get value from field:[{}], detail:{}",
                            field, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }
    
    private final Field[] _headerFields;
}
