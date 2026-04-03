package javax.management;

/** Stub for Android — javax.management is not available on Android. */
public class ReflectionException extends JMException {
    public ReflectionException(Exception e) { super(e != null ? e.getMessage() : null); }
    public ReflectionException(Exception e, String message) { super(message); }
}
