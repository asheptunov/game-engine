package logging;

public interface LogManager {
    static LogManager instance() {
        return LogManagerImpl.INSTANCE;
    }

    Logger root();

    Logger forName(String name);

    Logger forClass(Class<?> klass);

    Logger getThis();
}
