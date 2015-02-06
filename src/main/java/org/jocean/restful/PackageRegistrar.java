package org.jocean.restful;

import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.Path;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.ext.util.PackageUtils;

public class PackageRegistrar extends RegistrarImpl {

    public PackageRegistrar(final EventReceiverSource source) {
        super(source);
    }

    public String getScanPackage() {
        return _scanPackage;
    }

    public void setScanPackage(final String scanPackage) {
        this._scanPackage = scanPackage;
        Set<Class<?>> classes = new HashSet<>();
//        List<String> paths = new ArrayList<>();
        for (Class<?> clz : PackageUtils.findClassesInPackage(scanPackage, Path.class)) {
            if (AbstractFlow.class.isAssignableFrom(clz)) {
                classes.add(clz);
//                paths.add(clz.getAnnotation(Path.class).value());
            }
        }
        this.setClasses(classes);
    }

    private String _scanPackage;
}
