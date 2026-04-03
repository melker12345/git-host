package javax.management;

/** Stub for Android — javax.management is not available on Android. */
public interface MBeanServer {
    ObjectName registerMBean(Object object, ObjectName name)
            throws InstanceAlreadyExistsException, MBeanRegistrationException,
                   NotCompliantMBeanException;
    void unregisterMBean(ObjectName name)
            throws MBeanRegistrationException;
}
