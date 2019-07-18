package rtmath.utilities;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static rtmath.utilities.ResourceLoaderUtils.closeLockedFileChannel;
import static rtmath.utilities.ResourceLoaderUtils.openLockedFileChannel;

/**
 * Set of helper methods mostly for checking directory access locks and performing cleanup.
 */
public class FileJanitor {
    private static final String lockFileName = "lockfile.$$$";

    private static boolean isLockFile(Path path) {
        return path.getFileName().endsWith(lockFileName);
    }

    private static FileLock tryOpenForWriteTest(Path path) {

        // NOTE: We previously had DELETE_ON_CLOSE here but on Linux the file is deleted immediately after opening!
        // Delete the file yourself, please
        try {
            return openLockedFileChannel(path
                , StandardOpenOption.CREATE
                , StandardOpenOption.READ
                , StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            return null;
        }
    }

    static boolean tryDelete(Path path) {
        try {
            Files.delete(path);
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }


    public static Path lockFilePath(Path dir) {
        return dir.resolve(lockFileName);
    }

    static class LockFile implements Closeable {
        Path path;
        FileLock lock;

        LockFile(FileLock lock, Path path) {
            this.lock = lock;
            this.path = path;
        }


        @Override
        public void close() {

            if (null != lock) {
                closeLockedFileChannel(lock);
                tryDelete(path);
                lock = null;
            }
        }

        protected void finalize() throws Throwable {
            close();
        }

        public FileChannel channel() {
            return lock.channel();
        }
    }

    public static LockFile tryCreateLockFile(Path dir) {
        dir = lockFilePath(dir);
        FileLock lock = tryOpenForWriteTest(dir);
        return null != lock ? new LockFile(lock, dir) : null;
    }

    public static boolean lockFileExists(Path dir)
    {
        return Files.exists(lockFilePath(dir));
    }

    public static long getLockFileWriteTime(Path path) {
        try {
            BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
            return attr.lastAccessTime().toMillis();
        }
        catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Delete directory carefully, only if _none_ of the files in it are opened by someone else.
     * <p>
     * If at least one file is locked, the operation silently fails without deleting _anything_.
     * <p>
     * It is safe to call this operation concurrently on a single directory, but if the directory contents are modified
     * by the code that does not respect our lock file, its contents may still be deleted partially
     * and false will be returned.
     *
     * @param dir path being deleted
     * @return <code>true</code> if was able to delete all the files in the directory without triggering any locks.
     * <code>false</code> in no changes were made to the contents of the directory (except the case described above).
     */
    public static boolean tryDeleteDirectory(Path dir) {
        boolean isSuccess = false;
        LockFile lock = tryCreateLockFile(dir);

        if (null == lock)
            return false;

        try {
            List<FileLock> openedFiles = new ArrayList<>();
            ArrayList<Path> foundFiles = new ArrayList<>();

            ListDeleteableFiles:
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(dir)) {
                for (Path path : paths) {
                    if (isLockFile(path))
                        continue;

                    FileLock fl;
                    if (Files.isDirectory(path) || null == (fl = tryOpenForWriteTest(path)))
                        break ListDeleteableFiles;

                    openedFiles.add(fl);
                    foundFiles.add(path);
                }

                isSuccess = true;
            }

            for (FileLock fl : openedFiles)
                closeLockedFileChannel(fl);

            if (isSuccess) {
                for (Path path : foundFiles) {
                    if (!isLockFile(path) && !tryDelete(path))
                        return false;
                }
            }
        } catch (IOException e) {}
        finally {
            lock.close();
        }

        return isSuccess ? tryDelete(dir) : false;
    }

    /**
     * Register path for cleanup, call cleanup immediately.
     *
     * @param dir           path to clean
     * @param cleanDir      if true, will clean the specified directory, ignoring subdirectories (default).
     * @param subDirRegEx   if not null, will try to clean all subdirectories whose name matches this RegEx (not recursive).
     */
    public static boolean tryCleanup(String dir, boolean cleanDir, String subDirRegEx) {
        return new CleanupPath(Paths.get(dir), cleanDir, subDirRegEx).TryCleanup();
    }

    /**
     * Register path for cleanup, call cleanup immediately.
     *
     * @param dir           path to clean
     */
    public static boolean tryCleanup(String dir) {
        return tryCleanup(dir, true, null);
    }

    /**
     * Activate cleanup procedure for all previously registered paths.
     */
    public static void tryCleanup() {

        synchronized (_cleanupLock) {
            List<CleanupPath> deleted = new ArrayList<>();

            for (CleanupPath p : _cleanupDirs)
                if (p.TryCleanup())
                    deleted.add(p);

            for (CleanupPath p : deleted)
                _cleanupDirs.remove(p);
        }
    }

    /**
     * Register path for cleanup.
     *
     * @param path          path to clean
     * @param cleanDir      if true, will clean the specified directory, ignoring subdirectories (default).
     * @param subDirRegEx   if not null, will try to clean all subdirectories whose name matches this RegEx (not recursive).
     */
    public static void addCleanupPath(Path path, boolean cleanDir, String subDirRegEx) {
        synchronized (_cleanupLock) {
            _cleanupDirs.add(new CleanupPath(path, cleanDir, subDirRegEx));
        }
    }

    /**
     * Register path for cleanup.
     *
     * @param path          path to clean
     */
    public static void addCleanupPath(Path path) {
        addCleanupPath(path, true, null);
    }

    /**
     * Register FileJanitor's on-exit cleanup callback.
     * <p>
     * May be called several times but will only have effect once.
     */
    public static void registerForCleanupOnExit() {

        synchronized (_cleanupLock) {
            if (!_handlerRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    public void run() {
                        tryCleanup();
                    }
                }));
                _handlerRegistered = true;
            }
        }
    }

    private static boolean _handlerRegistered;

    private static Object _cleanupLock = new Object();
    private static List<CleanupPath> _cleanupDirs = new ArrayList<>();

    static class CleanupPath {
        private final Path _path;
        private final String _subDirRegEx;
        private final int _flags;

        private final int CLEAN_DIR = 1;
        public boolean TryCleanup() {
            try {
                if (!Files.exists(_path))
                    return true;

                boolean success = true;
                // Clean subdirs?
                if (null != _subDirRegEx) {
                    Pattern p = Pattern.compile(_subDirRegEx);
                    Matcher m = p.matcher("");
                    try (DirectoryStream<Path> paths = Files.newDirectoryStream(_path)) {
                        for (Path childDir : paths) {
                            if (Files.isDirectory(childDir)) {
                                m.reset(childDir.toString());
                                if (m.find())
                                    success &= FileJanitor.tryDeleteDirectory(childDir);
                            }
                        }
                    }
                }

                if (0 != (_flags & CLEAN_DIR))
                    success &= FileJanitor.tryDeleteDirectory(_path);

                return success;
            } catch (IOException e) {
                return false;
            }
        }

        public CleanupPath(Path path, boolean cleanDir, String subDirRegEx) {
            _path = path;
            _subDirRegEx = subDirRegEx;
            _flags = (cleanDir ? CLEAN_DIR : 0);
        }
    }
}


