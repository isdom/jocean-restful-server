package org.jocean.restful;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.ext.util.PackageUtils;
import org.jocean.idiom.InterfaceSource;

import javax.ws.rs.Path;
import java.util.*;

public class PackageRegistrarWithZK extends RegistrarImpl {

    private String scanPackage;
    private Map<String, Object> zkNodeData = new HashMap<>();

    public PackageRegistrarWithZK(final EventReceiverSource source) {
        super(source);
    }

    public String getScanPackage() {
        return scanPackage;
    }

    public void setScanPackage(String scanPackage) {
        this.scanPackage = scanPackage;
        Set<Class<?>> classes = new HashSet<>();
        List<String> paths = new ArrayList<>();
        for (Class<?> clz : PackageUtils.findClassesInPackage(scanPackage, Path.class)) {
            if (InterfaceSource.class.isAssignableFrom(clz)) {
                classes.add(clz);
                paths.add(clz.getAnnotation(Path.class).value());
            }
        }
        zkNodeData.put("paths", paths);
        this.setClasses(classes);
    }

    public Map<String, Object> getZkNodeData() {
        return this.zkNodeData;
    }
}
