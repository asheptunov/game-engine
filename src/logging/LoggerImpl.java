package logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static logging.LoggerImpl.Level.DEBUG;
import static logging.LoggerImpl.Level.ERROR;
import static logging.LoggerImpl.Level.FATAL;
import static logging.LoggerImpl.Level.INFO;
import static logging.LoggerImpl.Level.TRACE;
import static logging.LoggerImpl.Level.WARN;

public class LoggerImpl implements Logger {
    private static final Locale            LOCALE                              = Locale.ROOT;
    private static final DateTimeFormatter DEFAULT_DATE_TIME_FORMATTER         = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(ISO_LOCAL_DATE)
            .appendLiteral('T')
            .append(new DateTimeFormatterBuilder()
                    .appendValue(HOUR_OF_DAY, 2)
                    .appendLiteral(':')
                    .appendValue(MINUTE_OF_HOUR, 2)
                    .optionalStart()
                    .appendLiteral(':')
                    .appendValue(SECOND_OF_MINUTE, 2)
                    .optionalStart()
                    .appendFraction(NANO_OF_SECOND, 9, 9, true)
                    .toFormatter(LOCALE))
            .toFormatter(LOCALE);
    private static final String            DEFAULT_NEWLINE                     = "\\n";
    private static final String            DEFAULT_CARRIAGE_RETURN             = "\\r";
    private static final String            DEFAULT_STACK_TRACE_NEWLINE         = "|";
    private static final String            DEFAULT_STACK_TRACE_CARRIAGE_RETURN = "";

    private final String            name;
    private final Level             min;
    private final Clock             clock;
    private final DateTimeFormatter dateTimeFormatter        = DEFAULT_DATE_TIME_FORMATTER;
    private final String            newline                  = DEFAULT_NEWLINE;
    private final String            carriageReturn           = DEFAULT_CARRIAGE_RETURN;
    private final String            stackTraceNewline        = DEFAULT_STACK_TRACE_NEWLINE;
    private final String            stackTraceCarriageReturn = DEFAULT_STACK_TRACE_CARRIAGE_RETURN;

    LoggerImpl(String name, Level min, Clock clock) {
        this.name = name;
        this.min = min;
        this.clock = clock;
    }

    enum Level {
        TRACE(System.out),
        DEBUG(System.out),
        INFO(System.out),
        WARN(System.err),
        ERROR(System.err),
        FATAL(System.err);

        private final PrintStream ps;
        private final int         len;

        Level(PrintStream ps) {
            this.ps = ps;
            this.len = this.name().length();
        }

        private static final int                LONGEST = Arrays.stream(Level.values())
                .map(Enum::name)
                .map(String::length)
                .max(Integer::compare)
                .orElseThrow();
        private static final Map<Level, String> PADDING = Arrays.stream(Level.values())
                .collect(Collectors.toMap(t -> t, t -> " ".repeat(LONGEST - t.len)));

        private String padding() {
            return PADDING.get(this);
        }
    }

    private Logger log(Level level, Throwable ex, String fmt, Object... args) {
        if (level.ordinal() < min.ordinal()) {
            return this;
        }
        log(level, fmt, args);
        var os = new ByteArrayOutputStream();
        var ps = new PrintStream(os);
        ex.printStackTrace(ps);
        ps.flush();
        ps.close();
        try {
            os.flush();
            os.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var str = os.toString(StandardCharsets.UTF_8)
                .trim()
                .replace("\n", stackTraceNewline)
                .replace("\r", stackTraceCarriageReturn);
        level.ps.println(str);
        return this;
    }

    private Logger log(Level level, String fmt, Object... args) {
        if (level.ordinal() < min.ordinal()) {
            return this;
        }
        level.ps.printf("%s %s[%s] %s: %s%n",
                dateTimeFormatter.format(LocalDateTime.now(clock)),
                level.padding(),
                level.name(),
                name,
                String.format(fmt, args)
                        .replace("\n", newline)
                        .replace("\r", carriageReturn));
        return this;
    }

    @Override
    public Logger fatal(Throwable ex, String fmt, Object... args) {
        return log(FATAL, ex, fmt, args);
    }

    @Override
    public Logger fatal(String fmt, Object... args) {
        return log(FATAL, fmt, args);
    }

    @Override
    public Logger error(Throwable ex, String fmt, Object... args) {
        return log(ERROR, ex, fmt, args);
    }

    @Override
    public Logger error(String fmt, Object... args) {
        return log(ERROR, fmt, args);
    }

    @Override
    public Logger warn(Throwable ex, String fmt, Object... args) {
        return log(WARN, ex, fmt, args);
    }

    @Override
    public Logger warn(String fmt, Object... args) {
        return log(WARN, fmt, args);
    }

    @Override
    public Logger info(String fmt, Object... args) {
        return log(INFO, fmt, args);
    }

    @Override
    public Logger info(Object obj) {
        return info("%s", obj);
    }

    @Override
    public Logger debug(String fmt, Object... args) {
        return log(DEBUG, fmt, args);
    }

    @Override
    public Logger debug(Object obj) {
        return debug("%s", obj);
    }

    @Override
    public Logger trace(String fmt, Object... args) {
        return log(TRACE, fmt, args);
    }

    @Override
    public Logger trace(Object obj) {
        return trace("%s", obj);
    }
}
