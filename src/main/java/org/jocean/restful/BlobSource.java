package org.jocean.restful;

import org.jocean.netty.BlobRepo.Blob;

import rx.Observable;
import rx.functions.Action1;

public interface BlobSource {
    
    public Observable<? extends Blob> toBlobs(
            final String contentTypePrefix, final boolean releaseRequestASAP);
    
    public Action1<Blob> releaseBlob();
}
