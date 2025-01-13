package logging;

public interface Logger {
    Logger fatal(Throwable ex, String fmt, Object... args);

    Logger fatal(String fmt, Object... args);

    Logger error(Throwable ex, String fmt, Object... args);

    Logger error(String fmt, Object... args);

    Logger warn(Throwable ex, String fmt, Object... args);

    Logger warn(String fmt, Object... args);

    Logger info(String fmt, Object... args);

    Logger info(Object obj);

    Logger debug(String fmt, Object... args);

    Logger debug(Object obj);

    Logger trace(String fmt, Object... args);

    Logger trace(Object obj);
}
