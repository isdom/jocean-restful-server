package org.jocean.json;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;

public class FastJSONProvider implements JSONProvider {
    @Override
    public String toJSONString(Object object) {
        return JSON.toJSONString(object, SerializerFeature.PrettyFormat);
    }
}
