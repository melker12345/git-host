package javax.management;

/** Stub for Android — javax.management is not available on Android. */
public class MBeanException extends JMException {
    public MBeanException(Exception e) { super(e != null ? e.getMessage() : null); }
    public MBeanException(Exception e, String message) { super(message); }
}
