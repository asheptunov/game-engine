package logging;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LogManagerImpl implements LogManager {
    static final LogManagerImpl INSTANCE = new LogManagerImpl();

    private final Map<String, Logger>           loggerFlyweight = new ConcurrentHashMap<>();
    private final Map<String, LoggerImpl.Level> levels          = new ConcurrentHashMap<>();
    private final Clock                         clock           = Clock.systemDefaultZone();

    private LogManagerImpl() {
        var pattern = Pattern.compile(String.format("^(root|[a-zA-Z][a-zA-Z0-9\\._]*)=(%s)$",
                Arrays.stream(LoggerImpl.Level.values()).map(Enum::name).collect(Collectors.joining("|"))));
        try (var propFileStream = this.getClass().getClassLoader().getResourceAsStream("logging.txt")) {
            if (propFileStream != null) {
                var reader = new BufferedReader(new InputStreamReader(propFileStream));
                var line = reader.readLine();
                var i = 1;
                while (line != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    var matcher = pattern.matcher(line);
                    if (!matcher.find()) {
                        System.err.printf("malformed logging.txt on line %d: %s; should be empty or match %s%n",
                                i, line, pattern.pattern());
                        System.exit(1);
                    }
                    var name = matcher.group(1);
                    var level = LoggerImpl.Level.valueOf(matcher.group(2));
                    if (levels.containsKey(name)) {
                        System.err.printf("duplicate log level definition in logging.txt on line %d: %s; previously set to %s%n",
                                i, line, levels.get(name));
                        System.exit(1);
                    }
                    levels.put(name, level);
                    line = reader.readLine();
                    ++i;
                }
            }
        } catch (IOException ignored) {}
        levels.putIfAbsent("root", LoggerImpl.Level.INFO);
    }

    @Override
    public Logger root() {
        return forName("root");
    }

    @Override
    public Logger forName(String name) {
        var level = levels.getOrDefault(name, levels.get("root"));
        return loggerFlyweight.computeIfAbsent(name, n -> new LoggerImpl(name, level, clock));
    }

    @Override
    public Logger forClass(Class<?> klass) {
        return forName(klass.getCanonicalName());
    }

    @Override
    public Logger getThis() {
        boolean foundCallingClass = false;
        for (var frame : Thread.currentThread().getStackTrace()) {
            var klassOpt = getClassFromFrame(frame);
            if (klassOpt.isEmpty()) {
                continue;
            }
            var klass = klassOpt.get();
            if (!foundCallingClass && this.getClass() == klass) {
                foundCallingClass = true;
                continue;
            }
            if (foundCallingClass) {
                return forClass(klass);
            }
        }
        return root();
    }

    private Optional<Class<?>> getClassFromFrame(StackTraceElement frame) {
        try {
            return Optional.of(Class.forName(frame.getClassName()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
