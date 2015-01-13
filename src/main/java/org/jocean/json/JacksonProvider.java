package org.jocean.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JacksonProvider implements JSONProvider {
    private static final Logger LOG = LoggerFactory.getLogger(JacksonProvider.class);
    //http://wiki.fasterxml.com/JacksonFAQThreadSafety ObjectMapper是线程安全的
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String toJSONString(Object object) {
        try {
            JsonViewable jsonViewable = object.getClass().getAnnotation(JsonViewable.class);
            if (jsonViewable == null) {
                return mapper.writeValueAsString(object);
            }
            return mapper.writerWithView(jsonViewable.value()).writeValueAsString(object);
        } catch (JsonProcessingException e) {
            LOG.error("", e);
        }
        return null;
    }
}
