/**
 * 
 */
package org.jocean.restful;

/**
 * @author isdom
 *
 */
public class Events {
    private Events() {
        throw new IllegalStateException("No instances!");
    }
    
    public static final String ON_FILEUPLOAD = "onFileUpload";
    public static final String ON_FILEUPLOAD_COMPLETED = "onFileUploadCompleted";
}
