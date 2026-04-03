package javax.management;

/** Stub for Android — javax.management is not available on Android. */
public class MBeanRegistrationException extends JMException {
    public MBeanRegistrationException(Exception e) { super(e != null ? e.getMessage() : null); }
    public MBeanRegistrationException(Exception e, String message) { super(message); }
}
