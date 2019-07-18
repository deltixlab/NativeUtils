package rtmath.utilities;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Printing/logging, lock file helpers, OS/Platform stuff, string helpers, etc.
final class ResourceLoaderUtils {
    public static final int DBG = 0;
    public static final int INF = 2;
    public static final int ERR = 4;

    // Currently, we only enable logging during debugging
    public static final int LogLevel = ERR;

    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSSSSS");

    private static ArrayList<PrintStream> _logSinks = new ArrayList<>();
    private static StringBuilder _logSb = new StringBuilder();

    static String fmt(String fmt, Object... args) {
        return String.format(fmt, args);
    }

    static IllegalArgumentException argException(String fmt, Object... args) {
        return argException(null, fmt, args);
    }

    static IllegalArgumentException argException(Throwable cause, String fmt, Object... args) {
        return new IllegalArgumentException(fmt(fmt, args), cause);
    }

    private static void logLog(String v) {
        synchronized (_logSinks) {
            StringBuilder sb =
            new StringBuilder().append(ManagementFactory.getRuntimeMXBean().getName())
                .append(' ')
                .append(sdf.format(Calendar.getInstance().getTime()))
                .append(':').append(v).append('\n');

            if (0 == _logSinks.size()) {
                System.out.print(sb.toString());
            } else {
                for (PrintStream w : _logSinks)
                    w.print(sb.toString());
            }
        }
    }

    static boolean logLevelLeast(int loglevel) {
        return loglevel >= LogLevel;
    }

    static void log(int loglevel, String fmt, Object... args) {
        if (loglevel >= LogLevel)
            logLog(fmt(fmt, args));
    }

    static void log(int loglevel, String fmt) {
        if (loglevel >= LogLevel)
            logLog(fmt);
    }

    private static void log0(int loglevel, String fmt, Object... args) {
        log(loglevel, fmt, args);
    }

    private static void log0(String fmt, Object... args) {
        log(0, fmt, args);
    }

    static void log(String fmt, Object... args) {
        log0(fmt, args);
    }

    static void log(int loglevel, String fmt, Object arg0) {
        log0(loglevel, fmt, arg0);
    }

    static void log(int loglevel, String fmt, Object arg0, Object arg1) {
        log0(loglevel, fmt, arg0, arg1);
    }

    static void log(int loglevel, String fmt, Object arg0, Object arg1, Object arg2) {
        log0(loglevel, fmt, arg0, arg1, arg2);
    }

    static void log(String fmt, Object arg0) {
        log0(fmt, arg0);
    }

    static void log(String fmt, Object arg0, Object arg1) {
        log0(fmt, arg0, arg1);
    }

    static void log(String fmt, Object arg0, Object arg1, Object arg2) {
        log0(fmt, arg0, arg1, arg2);
    }

    static void log(String fmt) {
        log(0, fmt);
    }

    static void addSink(PrintStream to) {
        if (null == to)
            to = System.out;

        synchronized (_logSinks) {
            _logSinks.add(to);
        }
    }

    static String dt2str(long millis) {
        return sdf.format(millis);
    }

    /**
     * Open FileChannel and take FileLock, shared for read access, exclusive for write access.
     * Guaranteed to not change the file's contents if the lock can't be taken (except possibly create it, if not exists)
     * @param filePath
     * @param options
     * @return
     * @throws IOException if error has occured or if unable to take a lock.
     */
    static FileLock openLockedFileChannel(Path filePath, OpenOption... options) throws IOException {
        boolean truncate = false, shared = true;
        Set<OpenOption> set = new HashSet<>(4);
        set.add(StandardOpenOption.READ);

        for (OpenOption i : options) {
            if (i == StandardOpenOption.TRUNCATE_EXISTING) {
                shared = false;
                truncate = true;
                // DO NOT add this option to the list.
                continue;
            } else if (i == StandardOpenOption.APPEND || i == StandardOpenOption.WRITE || i == StandardOpenOption.CREATE_NEW) {
                shared = false;
            }

            set.add(i);
        }

        FileChannel fileChannel = FileChannel.open(filePath, set, new FileAttribute[0]);
        FileLock fileLock = null;

        try {
            fileLock = fileChannel.tryLock(0, Long.MAX_VALUE, shared);
            if (null == fileLock) {
                log("tryLock(shared=%s): File is locked by another process! : %s", shared, filePath);
                throw new IOException(fmt("tryLock(shared=%s): File is locked by another process! : %s", shared, filePath));
            }

            log("Lock at: %s", filePath);

            if (truncate) {
                fileChannel.truncate(0);
                fileChannel.force(true);
            }

            fileChannel.position(0);
            return fileLock;
        }
        catch (Throwable e) {
            if (null != fileLock)
                fileLock.close();

            fileChannel.close();
            if (e instanceof OverlappingFileLockException)
                throw new IOException(fmt("tryLock(shared=%s): File is locked by this JVM instance! : %s", shared, filePath));

            throw e;
        }
    }

    static void closeLockedFileChannel(FileLock fl) {

        if (null != fl) {
            FileChannel fc = fl.channel();
            try {
                fl.close();
            }
            catch (IOException e) {}
            try {
                fc.close();
            }
            catch (IOException e) {}
        }
    }

    public static String shortenBy(String str, int n) {
        return str.substring(0, str.length() - n);
    }


    static final class OS {
        static final int Windows = 0;
        static final int Linux = 1;
        static final int Osx = 2;

        static final int _dataModel = Integer.parseInt(System.getProperty("sun.arch.data.model"));
        static final String[] _osNames = {"Windows", "Linux", "OSX"};
        static final String[] _dllExts = {".dll", ".so", ".dylib"};
        private static final int _os;

        public static boolean is64() {
            return 64 == _dataModel;
        }

        public static String name() {
            return _osNames[_os];
        }

        public static boolean isLinux() {
            return Linux == _os;
        }

        public static boolean isWindows() {
            return Windows == _os;
        }

        public static boolean isOsx() {
            return Osx == _os;
        }

        public static boolean isUnix() {
            return Windows != _os;
        }

        public static String getVersion(Class aClass) {
            String ver = aClass.getPackage().getImplementationVersion();
            return null != ver ? ver : "0";
        }

        public static String dllExt() {
            return _dllExts[_os];
        }

        public static boolean isDllExt(String s) {
            return dllExt().equals(s);
        }

        static {
            String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            _os = osName.contains("windows") ? 0 : osName.contains("linux") ? 1 : osName.contains("mac") ? 2 : -1;
        }
    }

    /**
     * Utility class for basic template substitution
     */
    static final class TemplateString {
        public static String verify(String substituted) {
            int iStart = substituted.indexOf("$(");

            if (iStart >= 0) {
                int iEnd = substituted.indexOf(")", iStart);
                int iStart2 = substituted.indexOf("$(", iStart + 1);

                throw argException("Template substitution error: %s at position %s: %s",
                    iEnd < 0 || iStart2 <= iEnd ? "Key not terminated" : "Unknown/unexpected key",
                    iStart, substituted.substring(iStart));
            }

            return substituted;
        }

        private static String substitute1(String template, String key, String value) {
            return template.replace("$(" + key + ')', value);
        }

        private static boolean containsKey(String template, String key) {
            return template.indexOf("$(" + key + ')') >= 0;
        }

        private static boolean containsKey(String template, String... keys) {

            for (String key : keys)
                if (containsKey(template, key))
                    return true;

            return false;
        }

        public static String substitute(String template, String... keyValuePairs) {
            int n = keyValuePairs.length;

            if (0 != (n & 1))
                throw new IllegalArgumentException("kvps.Length must be odd value");

            for (int i = 0; i < n; i += 2)
                template = substitute1(template, keyValuePairs[i], keyValuePairs[i + 1]);

            return template;
        }
    }

    static String getTags(String str, Map<String, String> tags) {
        Pattern p = Pattern.compile("\\[([^@\\]]*)@([^@\\]]*)\\]");
        Matcher m = p.matcher(str);

        if (null != tags) {
            while (m.find()) {
                tags.put(m.group(1), m.group(2));
            }
        }

        return m.replaceAll("");
    }
}
