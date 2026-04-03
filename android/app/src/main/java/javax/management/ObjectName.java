package javax.management;

/** Stub for Android — javax.management is not available on Android. */
public class ObjectName {
    private final String name;

    public ObjectName(String name) throws MalformedObjectNameException {
        this.name = name;
    }

    public static ObjectName getInstance(String name) throws MalformedObjectNameException {
        return new ObjectName(name);
    }

    @Override public String toString() { return name; }
    @Override public int hashCode() { return name != null ? name.hashCode() : 0; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ObjectName)) return false;
        ObjectName that = (ObjectName) o;
        return name != null ? name.equals(that.name) : that.name == null;
    }
}
