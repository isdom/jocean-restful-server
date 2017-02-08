package org.jocean.restful;

import org.jocean.netty.util.ReferenceCountedHolder;

public interface ReferenceCountedHolderAware {
    public void setHolder(final ReferenceCountedHolder holder);

}
