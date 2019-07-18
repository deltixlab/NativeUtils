package rtmath.utilities;

import rtmath.zstd.ZstdDecompressor;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.lang.System.*;
import static rtmath.utilities.ResourceLoaderUtils.*;


/**
 * User-friendly class for deploying a set of resources from JAR archive or resource path of an IDE.
 * <p>It accepts resource path template describing what set of resources to deploy depending on the operating system
 * (Windows/Linux/OSX) and CPU pointer size (32/64).
 * <p>Currently will always assume x86/x64 platform, no distinction made between other platforms.</>
 * <p>Will decompress files compressed with ZStandard archiver before deploying.
 * <p>Will load deployed Dynamic Libraries into memory, so they can be used via JNI(Java Native Interface).
 * <p>Can deploy to several possible deployment paths and can handle conflicts with other instances of itself
 * simultaneously trying to deploy to the same location
 * <p>Supports concurrent deployment and shared use of the same native library by Java and C# programs
 * <p>Can delete resources deployed to a temporary location on exit.
 * <p>Tries to protect loaded DLs from accidental deletion or corruption by other instances.
 * <p>
 * Originally based on native-utils project, but completely rewritten since then.
 *
 * @see <a href="http://adamheinrich.com/blog/2012/how-to-load-native-jni-library-from-jar">How to load native jni library from jar</a>
 * @see <a href="https://github.com/adamheinrich/native-utils">GitHub</a>
 */
public class ResourceLoader implements ResourceLoaderInstance, ResourceLoaderDone {

    static class Resource implements Closeable, Comparable<Resource> {
        private static final int STREAM_URL = 1;
        private static final int FILE_PATH = 2;
        private static final int JAR_FILE = 3;

        private final ResourceLoader owner;
        public final int type;
        public final Object source;     // Source. Either Path or URL
        public final String name;       // Original resource filename, without path
        public final String filename;   // Filename only for the deployed resource.

        public final boolean isZstd;    // Needs decompression from ZStd
        public final boolean isDll;     // Is a dynamic library, will be loaded into memory (unless loading disabled)

        public final int length;        // Original file length, before decompression, less than 2GB
        public final int order;

        private FileLock _fileLock;
        private boolean _isLoaded;      // Corresponds to DlHandle in C# version

        public Resource(final Path resourcePath
            , Object source
            , int type, int initialOrder, long length
            , final ResourceLoader owner)
            throws IOException {

            this.owner = owner;
            this.source = source;
            this.type = type;
            log("Adding resource file: %s", resourcePath);

            HashMap<String, String> tags = new HashMap<>();
            String resourceName = resourcePath.getFileName().toString();
            String fileName = this.name = getTags(resourceName, tags)
                .replace('_', '.');

            this.isZstd = fileName.endsWith(".zst");
            if (isZstd)
                fileName = ResourceLoaderUtils.shortenBy(fileName, 4);

            // Owner can optionally rename the resource
            this.filename = fileName = owner.tryRenameResource(fileName);
            this.isDll = filename.endsWith(OS.dllExt());

            // Parse file order tags
            int order = initialOrder;
            for (Map.Entry<String,String> kv : tags.entrySet()) {
                String key = kv.getKey();
                String value = kv.getValue();
                if (key.equals("order")) {
                    order = -1;
                    try {
                        order = Integer.parseInt(value);
                    }
                    catch (NumberFormatException e) {}

                    if (order < 0)
                        throw argException("Order tag invalid, non-negative integer expected: [order@%s]", value);

                    order += Integer.MIN_VALUE; // Needed to combine natural order and explicit order
                } else
                    throw argException("Invalid Tag: [%s@%s]", key, value);
            }

            this.order = order;
//            if (length < 0)
//                length = STREAM_URL == type ? ((URL)source).openConnection().getContentLength() : Files.size((Path)source);

            log( "Length: %s", length);
            if (length < 0)
                throw argException("Resource file '%s' length is negative: %s", resourcePath, length);

            if (length > Integer.MAX_VALUE)
                throw argException("Resource file '%s' length is too big: %s", resourcePath, length);

            this.length = (int)length;
            owner.onResourceAdded(this);
        }

        Path getFullPath(final Path deploymentPath) {
            return deploymentPath.resolve(filename);
        }

        FileLock moveFileLock() {
            FileLock fl = _fileLock;
            _fileLock = null;
            return fl;
        }

        FileChannel getFile() { return null == _fileLock ? null : _fileLock.channel(); }

        void setFileLock(final FileLock newValue) {

            if (newValue != _fileLock) {
                closeLockedFileChannel(_fileLock);
                _fileLock = newValue;
            }
        }

        // IDisposable Support
        @Override
        public void close() {
            setFileLock(null);
        }

        public void setReadLock(Path filePath) throws IOException {
            FileLock fl = openLockedFileChannel(filePath, StandardOpenOption.READ);
            assert(null != fl);
            setFileLock(fl);
        }

        public boolean isLoaded() {
            return _isLoaded;
        }

        public void setLoaded(boolean loaded) {
            _isLoaded = loaded;
        }

        // Natural order returned by this comparator is used as LoadLibrary order
        @Override
        public int compareTo(Resource o) {
            return Integer.compare(order, o.order);
        }

        public RlInputStream openSourceStream() throws IOException {
            return  STREAM_URL == type ? RlInputStream.wrap(((URL)source).openStream(), length) :
                    FILE_PATH == type ? RlInputStream.wrap(FileChannel.open((Path)source, StandardOpenOption.READ), length) :
                        ((JarFs)owner._jarFileSystem).get((String)source);
        }
    }

    /**
     * Builder helper class that asks you for the destination path template.
     */
    public static class From {
        private ResourceLoader _rl;

        From(ResourceLoader rl) { _rl = rl; }

        public String getActualResourcePath() { return _rl.getActualResourcePath(); }
        /**
         * Set deployment path (absolute or relative).
         * If a relative path is specified, will try it with several possible root path, until deployment succeeds.
         * @param deploymentPathTemplate Deployment path template
         * @return Initialized {@code ResourceLoaderInstance}
         */
        public ResourceLoaderInstance to(String deploymentPathTemplate) {
            return _rl.toInternal(deploymentPathTemplate);
        }
    }

    /**
     * Builder helper class that asks you for the resource path template.
     */
    public static class To {
        private ResourceLoader _rl;
        To(ResourceLoader rl) { _rl = rl; }

        /**
         * Set resource path template. Resources matching the template will be deployed to the destination dir.
         * <p>The template may contain the asterisk character <code>*</code>, denoting variable part of the resource name that
         * will become the actual filename.
         * <p> More advanced search masks or Regular Expressions are not supported.
         * <p> All underscore ('_') characters in the filename will be replaced with '.' character.
         * Resources, whose names were ending with '.zst'/'_zst' will be decompressed by ZStandard with '.zst' suffix removed
         * <p>
         * The following variables will be substituted:
         * <ul>
         * <li>$(OS) =&gt; 'Windows' | 'Linux' | 'OSX' - name of the current OS platform</li>
         * <li>$(VERSION) =&gt; current package version</li>
         * <li>$(ARCH) =&gt; '32' | '64' - pointer size of the current architecture</li>
         * <li>$(DLLEXT) =&gt; 'dll' | 'so' | 'dylib' - dynamic library file extension for the current OS platform</li>
         * </ul>
         *
         * @param resourcePathTemplate Resource path template. Describes the source location of the deployed resource set.
         * @return Initialized {@code ResourceLoaderInstance}
         */
        public ResourceLoaderInstance from(String resourcePathTemplate) {
            return _rl.fromInternal(null, resourcePathTemplate);
        }
    }

    private static final int READ_WRITE_BLOCK_SIZE = 1 << 24;
    private static final String RANDOM_DIR_REGEX = "^[0-9a-fA-F]{4,8}$";

    private static ArrayList<FileLock> _lockedDlls = new ArrayList<>();
    private static final Random _rnd = new Random();
    private final ByteBuffer _dummyBuffer = ByteBuffer.wrap(new byte[1]);

    private Class _class;       // Corresponds to _assembly field in C# version

    // User-settable config flags
    private boolean _alwaysOverwrite;
    private boolean _reusePartiallyDeployed;
    private boolean _addRandomFallbackSubDirectory;
    private boolean _shouldLoadDlls = true;
    private boolean _verifyLength;  // Not used yet
    private boolean _verifyContent; // Not used yet

    // Other config flags
    private boolean _keepDllsLocked;

    // User-configurable paths
    private String _resourcePathTemplate;
    private String _deploymentPathTemplate;
    private String _libraryNameSuffix;      // Optional dynamic library name suffix, applied before file extension

    // Derived paths
    private String _resourcePath;           // Resource path for a single resource file, specified w/o wildcard
    private String _resourcePrefix;
    private String _resourceSuffix;

    // After-deployment paths
    private String _lastSuccessfulPath;
    private String _lastUsedPath;

    private Throwable _lastDeploymentException;

    // Multiprocess/multithread file access contention management
    private FileJanitor.LockFile _lockFile; // Lock file created during write operations
    private int _retryTimeoutMs;            // User-configurable
    private long _lockUpdatePeriodNs;
    private long _lockLastUpdateNs;

    // Resource set
    private ArrayList<Resource> _resources;
    private int _maxResourceLength;         // Maximum resource length before unpacking
    private int _totalResourceLength;
    private int _dlCount;

    // Buffer for the data read from resources. As big as the biggest resource.
    private byte[] _inputBuffer;

    // Buffer for the data decompressed from resources. Dynamically reallocated to be big enough to hold zstd-decoded data.
    // Not allocated if there is no ZStd compression
    private byte[] _outputBuffer;

    private Closeable _jarFileSystem; // For accessing JAR resources. Has 2 implementations. Closed on exit.

    private ResourceLoader() {
        _retryTimeoutMs = -1;
    }

    private static ResourceLoader newInstance() { return new ResourceLoader(); }

    private static String applyBasicTemplateNoVerify(String template, Class clazz) {
        return TemplateString.substitute(template
            , "DLLEXT", OS.dllExt().substring(1) /* Without dot */
            , "OS", OS.name()
            , "ARCH", OS.is64() ? "64" : "32"
            , "VERSION", OS.getVersion(null != clazz ? clazz : ResourceLoader.class)
        );
    }

    private static String applyBasicTemplate(String template, Class clazz) {
        return TemplateString.verify(applyBasicTemplateNoVerify(template, clazz));
    }


    private static String applyPathTemplate(String template, Class clazz) {
        String str = TemplateString.substitute(applyBasicTemplateNoVerify(template, clazz)
            , "RANDOM", "/" + nextRandomDirString()

//        , "COMMONAPPDATA", Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData)
//        , "LOCALAPPDATA", Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData)

            , "TEMP", System.getProperty("java.io.tmpdir")
        );

        return TemplateString.verify(str);
    }

    private Throwable getLastDeploymentException() {
        return _lastDeploymentException;
    }

    private byte[] getInputBuffer() {
        return null != _inputBuffer ? _inputBuffer : (_inputBuffer = new byte[_maxResourceLength]);
    }

    private static int rnd()
    {
        return _rnd.nextInt();
    }

    private static String nextRandomDirString()
    {
        return Integer.toHexString(rnd());
    }

    private static long randomSleep(long limitMs) {
        long millis = (rnd() & 0x1F) + 0x10;
        millis = Math.min(millis, Math.max(limitMs, 1));

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return millis;
    }


    private FileJanitor.LockFile setFileLock(final FileJanitor.LockFile newValue) {

        if (newValue != _lockFile) {
            if (null != _lockFile)
                _lockFile.close();

            _lockFile = newValue;
        }

        return _lockFile;
    }


    /**
     * Optionally rename the resource file before it is written to disk.
     * <p>We can add logic here that changes filenames for some purpose.
     * <p>Future impl will probably allow us to use Regexp.
     *
     * @param filename filename of the resource file without path component that will be used for saving it to disk
     * @return new filename for the resource
     */
    private String tryRenameResource(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        String ext;
        // Is a dynamic library? Rename, if new suffix is specified
        if (lastDotIndex >= 0 && OS.isDllExt(ext = filename.substring(lastDotIndex)) && null != _libraryNameSuffix) {
            // Append new extension to the library file name
            filename = filename.substring(0, lastDotIndex) + _libraryNameSuffix + ext;
        }

        return filename;
    }

    private void onResourceAdded(Resource resource) {
        int length = resource.length;

        if (length > _maxResourceLength)
            _maxResourceLength = length;

        _totalResourceLength += length;
        if (resource.isDll)
            ++_dlCount;
    }

    private void addResource(Path resourceFilePath, Object source, int type, int initialOrder, long length) throws IOException {
        _resources.add(new Resource(resourceFilePath, source, type, initialOrder, length, this));
    }

    URL tryGetResource(String name) {
        return _class.getResource(name);
    }

    URL tryFindSingleDll(String path) {
        int iPathEnd = path.lastIndexOf('/') + 1;
        String dlName = getTags(path.substring(iPathEnd).replace('.', '_'), null);

        if (dlName.endsWith("_zst"))
            dlName = shortenBy(dlName, 4);

        String ext = '_' + OS.dllExt().substring(1);
        if (!dlName.endsWith(ext) || (dlName = shortenBy(dlName, ext.length())).length() == 0)
            return null; // Does not seem to be a dynamic library, or the remaining filename is empty

        int i = path.indexOf(dlName, iPathEnd);
        if (i < 0)
            return null;

        // We have the position of the base DLL filename within the file
        String p = path.substring(0, iPathEnd);
        String q1 = path.substring(i + dlName.length());
        String q2 = q1.replace(OS.dllExt(), ext);
        URL url = null;
        for (int j = 0; j < 4 && null == url; ++j) {
            url = tryGetResource(p + ((j & 2) == 0 ? "lib" : "") + dlName + ((j & 1) == 0 ? q2 : q1));
        }

        return url;
    }

    private void listFsResources(URI uri) throws URISyntaxException, IOException {
        Path resourcesPath = Paths.get(uri);
        boolean isDir = Files.isDirectory(resourcesPath);

        if (!isDir)
            throw argException("Resource path is not a directory: %s", resourcesPath);

        // NOTE: TODO: No subdir traversal, all resources are in the same JAR directory
        DirectoryStream<Path> stream = Files.newDirectoryStream(resourcesPath); // May add glob pattern here instead
        int order = 0;

        for (Path filePath : stream) {
            // TODO: globbing code is not yet present
            //if (path.startsWith(_resourcePrefix) && path.endsWith(_resourceSuffix))
            if (!Files.isDirectory(filePath))
                addResource(filePath, filePath, Resource.FILE_PATH, order++, Files.size(filePath));
        }
    }

    private void listJarResources(ZipInputStream stream, String resourcePathPrefix) throws IOException {
        assert(null != stream);
        ZipEntry entry;
//        int pathLength = resourcePathPrefix.lastIndexOf('/');
//        String dirPath = resourcePathPrefix.substring(0, pathLength + 1);
        int pathLength = resourcePathPrefix.length();
        String dirPath = resourcePathPrefix;

        if (!dirPath.endsWith("/"))
            ++pathLength;

        log("DirPath: %s", dirPath);
        int order = 0;
        while (null != (entry = stream.getNextEntry())) {
            String path = entry.getName();
            if (path.startsWith(dirPath) && path.length() > pathLength) {
                addResource(Paths.get(path), path, Resource.JAR_FILE, order++, entry.getSize());
                log("%s, %s", entry, entry.getName());
            }
        }
    }

    private void listResources() throws URISyntaxException, IOException {

        _maxResourceLength = -1;
        _totalResourceLength = 0;
        _dlCount = 0;
        _resources = new ArrayList<>();

        // The problem with getResources is that it _searches_ for the resources passed as args and _does not_ give you
        // the ability to obtain actual JAR root. Therefore, we are passing our whole path.
        // This path CAN'T be in system-dependent format, therefore we CAN'T use standard Path APIs to process it.
        // This is a sad situation really.
        String srcPath = _resourcePath;
        boolean isSingleFile = null == _resourceSuffix;
        URL url;
        if (null == (url = tryGetResource(srcPath)) && (!isSingleFile || null == (url = tryFindSingleDll(srcPath))))
            throw argException("Unable to find any resources at path: %s", srcPath);

        URI uri = url.toURI();
        String scheme = url.toURI().getScheme();

        if (isSingleFile) {
            log( "File path: %s URL: %s", srcPath, url);
            addResource(Paths.get(srcPath), url, Resource.STREAM_URL, 0, url.openConnection().getContentLength());
            return;
        }

        if (scheme.equalsIgnoreCase("jar")) {
            String path = url.getPath();
            int i0 = path.indexOf(".jar!/");
            int i1 = path.lastIndexOf(".jar!/");
            if (i0 == i1) {
                log( "Simple JAR path: %s", url);
                FileSystem jarFs = FileSystems.newFileSystem(uri, Collections.<String, Object>emptyMap());
                _jarFileSystem = jarFs;
                listFsResources(jarFs.getPath(path.substring(i0 + 5)).toUri());
            } else {
                log( "Complex JAR path: %s", url);
                URL jarUrl = _class.getResource(path.substring(i0 + 5, i1 + 4));
                InputStream jarStream;
                if (null == jarUrl || null == (jarStream = jarUrl.openStream()))
                    throw argException("Unable to open outer JAR( %s ) for complex JAR path: %s", jarUrl, uri);

                try (ZipInputStream zStream = new ZipInputStream(jarStream)) {
                    _jarFileSystem = new JarFs(jarUrl);
                    listJarResources(zStream, path.substring(i1 + 6));
                }
            }
        } else if (scheme.equalsIgnoreCase("file")) {
            log( "IDE path: %s", uri);
            listFsResources(uri);
        } else {
            throw argException("Resource URI scheme unexpected: %s", uri);
        }

        if (_resources.size() < 1)
            throw argException("No resource files were found at the specified path: %s -> %s", srcPath, uri);

        if (logLevelLeast(DBG)) {
            // Show predefined deployment order
            // Will be later sorted before calling LoadLibrary
            Collections.sort(_resources);
            for (Resource resource : _resources)
                log("%s: %s", resource.order, resource.filename);
        }
    }


    private void lockFileWatchdogUpdate(boolean force) throws IOException {

        long now = nanoTime();
        long elapsed = now - _lockLastUpdateNs;

        if (null != _lockFile && (elapsed > _lockUpdatePeriodNs || force)) {
            _lockLastUpdateNs = now;
            FileChannel fc = _lockFile.channel();
            fc.position(0);
            _dummyBuffer.rewind();
            fc.write(_dummyBuffer);
            fc.force(true);
            log("LockFile updated");
        }
    }


    private void lockFileWatchdogUpdate() throws IOException {
        lockFileWatchdogUpdate(false);
    }


    private void lockFileWatchdogInit() {
        _lockLastUpdateNs = nanoTime() - _lockUpdatePeriodNs;
    }


    private void loadDynamicLibraries(Path deploymentPath) {
        int numLoaded = 0;
        boolean loadedLeastOne;
        Throwable exception = null;

        // Sort for loading
        Collections.sort(_resources);
        do {
            loadedLeastOne = false;
            for (Resource resource : _resources) {
                if (resource.isDll && !resource.isLoaded()) {
                    Path path = resource.getFullPath(deploymentPath);
                    if (logLevelLeast(DBG))
                        log("Loading DLL#%s: {path} , exists: %s, hasLockFile: %s",
                            numLoaded + 1, Files.exists(path), null != resource._fileLock);

                    try {
                        System.load(path.toString());
                    } catch(SecurityException|LinkageError e) {
                        exception = e;
                        if (logLevelLeast(DBG))
                            log("LoadLibrary Exception: %s", e.getMessage());

                        continue;
                    }

                    resource.setLoaded(true);
                    ++numLoaded;
                    loadedLeastOne = true;
                }
            }
        } while(loadedLeastOne);

        if (null != exception) {
            // Avoid 'throws'
            if (exception instanceof SecurityException) {
                throw (RuntimeException) exception;
            } else {
                throw (LinkageError) exception;
            }
        }
    }


    /**
     * Register loaded DL file handles to keep the files locked, protecting them from deletion/corruption
     */
    private void keepDllFileHandles() {

        for (Resource resource : _resources) {
            if (resource.isDll && null != resource._fileLock) {
                synchronized (_lockedDlls) {
                    _lockedDlls.add(resource.moveFileLock());
                }
            }
        }
    }

    // TODO: No support for unloading libraries. Java
    private void unloadDynamicLibraries() {
//        for (Resource resource : _resources) {
//            if (resource.isDll && resource._isLoaded) {
//                // NO way to unload libs
//                resource._isLoaded = false;
//            }
//        }
    }

    // Unused
    private void flushResourceFiles() throws IOException {
        IOException cachedException = null;

        for (Resource resource : _resources) {
            FileChannel file = resource.getFile();
            if (null != file) {
                try {
                    file.force(true);
                } catch (IOException e) {
                    if (null == cachedException)
                        cachedException = e;
                }
            }
        }

        if (null != cachedException)
            throw cachedException;
    }


    private void disposeResourceFiles() {

        for (Resource resource : _resources)
            resource.setFileLock(null);
    }

    // Read data to buffer starting from position() up to limit() in chunks no greater than READ_WRITE_BLOCK_SIZE
    private void readResourceFile(RlInputStream in, ByteBuffer buffer) throws IOException {

        int end = buffer.limit();
        int pos;
        while ((pos = buffer.position()) < end) {
            buffer.limit(Math.min(pos + READ_WRITE_BLOCK_SIZE, end));
            int numRead = in.read(buffer);
            if (numRead < 0)
                throw new IOException("Unable to read resource file, EOF encountered!");

            lockFileWatchdogUpdate();
        }
    }

    // Write data from buffer starting from position() up to limit() in chunks no greater than READ_WRITE_BLOCK_SIZE
    private void writeResourceFile(FileChannel channel, ByteBuffer buffer) throws IOException {
        int end = buffer.limit();

        while (buffer.position() < end) {
            buffer.limit(Math.min(buffer.position() + READ_WRITE_BLOCK_SIZE, end));
            channel.write(buffer);
            if (buffer.position() < end)
                channel.force(true);

            lockFileWatchdogUpdate();
        }
    }


    private static void renameLibraryIfNeeded(byte[] decompressedData, String from, String to) {

        if (!OS.isWindows() && to.length() != 0 && to.length() <= from.length()) {
            // If renaming Linux/OSX lib, try to also patch internal name before saving.
            // Extra chars are necessary to reserve space(from.length >= to.length). Temporary kludge that seems to work well.
            String suffix = OS.dllExt();
            replace(decompressedData, from + suffix, to + suffix);
        }
    }

    // Replace substring, 0-padding dst to src length
    private static void replace(byte[] data, String src, String dst) {

        assert (dst.length() <= src.length());
        int l1 = src.length();
        int l2 = dst.length();

        for (int i = 0, n = data.length - l1; i < n; ++i) {
            for (int j = 0; data[i + j] == src.charAt(j); ++j) {
                if (j == l1 - 1) {
                    for (int k = 0; k < l1; ++k)
                        data[i + k] = k < l2 ? (byte)dst.charAt(k) : 0;

                    return;
                }
            }
        }
    }


    private void deployResourcesInternal(Path deploymentPath) throws IOException {
        byte[] inputData = this.getInputBuffer();
        byte[] decompressedData = _outputBuffer;

        // Sort for deployment
        Collections.sort(_resources, new Comparator<Resource>() {
            @Override
            public int compare(Resource o1, Resource o2) {
                /* non-null(already existing files) before null */
                return  null != o1._fileLock && null == o2._fileLock ? -1 :
                        null == o1._fileLock && null != o2._fileLock ? 1 :
                        /* otherwise, descending order by size */
                        Integer.compare(o2.length, o1.length);
            }
        });

        for (Resource resource : _resources) {
            // If partial reuse is allowed and we already locked some files for read, do not deploy them
            if (null != resource.getFile())
                continue;

            Path filePath = resource.getFullPath(deploymentPath);
            byte[] outputData = inputData;

            log("Reading %s", filePath);
            int resourceLength = resource.length;
            int outputLength = resourceLength;

            try (RlInputStream in = resource.openSourceStream()) {
                // Note: the buffer may be bigger than the current file we are processing
                assert(resourceLength == in.size()); // Check for logic error, or ?
                readResourceFile(in, ByteBuffer.wrap(inputData, 0, resourceLength));
            }

            if (resource.isZstd) {
                long len = ZstdDecompressor.getDecompressedSize(inputData, 0, resourceLength);
                if (len > Integer.MAX_VALUE)
                    throw new RuntimeException(fmt("Decompressed file size is too big: %s for %s", outputLength, filePath));

                outputLength = (int)len;
                if (null == decompressedData || outputLength > decompressedData.length)
                    decompressedData = _outputBuffer = new byte[outputLength];

                outputData = decompressedData;
                ZstdDecompressor dec = new ZstdDecompressor();
                // TODO: May use ByteBuffer interface later
                dec.decompress(inputData, 0, resourceLength, decompressedData, 0, outputLength);
            }

            lockFileWatchdogUpdate();
            // Patch library internal name if it is changed during decompression.
            // This feature will be cleaned/improved in the future
            if (resource.isDll && null != _libraryNameSuffix)
                renameLibraryIfNeeded(outputData, "@@@@", _libraryNameSuffix);

            log("Writing %s", filePath);
            FileChannel out = null;
            try (FileLock lock = openLockedFileChannel(filePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                out = lock.channel();
                writeResourceFile(out, ByteBuffer.wrap(outputData, 0, outputLength));
                out.truncate(outputLength);
                log("Done writing %s, closing", filePath);
            }

            if (null != out)
                out.close();

            log("After writing %s, taking read lock", filePath);
            resource.setReadLock(filePath);
        }
    }


    private boolean verifyExistingResourceFiles(Path deploymentPath) throws IOException {

        // Verify automatically fails if overwrite mode is forced
        if (_alwaysOverwrite)
            return false;

        // Make sure all resource files are closed
        if (!_reusePartiallyDeployed)
            disposeResourceFiles();

        int timeout = _retryTimeoutMs;
        int numExpected = _resources.size();

        do {
            int numFound = 0, numOpened = 0;
            for (Resource resource : _resources) {
                Path filePath = resource.getFullPath(deploymentPath);
                if (Files.exists(filePath)) {
                    ++numFound;
                    if (null == resource.getFile()) {
                        try {
                            resource.setReadLock(filePath);
                        }
                        catch (IOException e) {
                            continue;
                        }

                        if (_verifyContent || _verifyLength) {
                            // TODO: File verification checks will go here. Currently we don't store the necessary metadata
                        }
                    }

                    ++numOpened;
                }
            }

            // Nothing found? Fail immediately
            if (0 == numFound)
                break;

            // All found? Return success
            if (numOpened == numExpected)
                return true;

            // Able to open some of the files
            if (numOpened == numFound)
                break;

            // If there is a lock file, fail
            if (FileJanitor.lockFileExists(deploymentPath))
                break;

            // Ok, we can't open _some_ of the files we found and there is no lock file. Probably being written, but not by this class.
            // Wait and retry until timeout
            timeout -= randomSleep(timeout);
        } while (timeout > 0);

        if (!_reusePartiallyDeployed)
            disposeResourceFiles();

        return false;
    }


    private void verifyOrDeployResources(Path deploymentPath) throws IOException {

        disposeResourceFiles();

        // Check, if we can load the existing resource files
        try {
            if (verifyExistingResourceFiles(deploymentPath)) {
                log("All files already deployed");
                return;
            }
        } catch (Throwable e) {
            log("1st Verify call threw");
            throw e;
        }

        long startTimeNs = nanoTime(), startTimeNs0 = startTimeNs;
        int retries = 3;
        final long timeout = _retryTimeoutMs;
        while (null == setFileLock(FileJanitor.tryCreateLockFile(deploymentPath))) {
            long elapsed = (nanoTime() - startTimeNs) / 1000_000; // To milliseconds
            // Yes, sleep at least once regardless of how much time remaining and re-check
            randomSleep(timeout - elapsed);
            if (elapsed < timeout || --retries >= 0)
                continue;

            long now = currentTimeMillis();
            // If lockfile is updated between retryTimeout in the past and retryTimeout * 10 in the future(!!), extend timer
            long lockFileAge = now - FileJanitor.getLockFileWriteTime(deploymentPath);
            if (lockFileAge < timeout && lockFileAge > -10 * timeout) {
                startTimeNs = nanoTime();
                retries = 3;
                log("Lock timer extended");
                continue;
            }

            double elapsedTotal = (nanoTime() - startTimeNs0) / 1E6;
            log(ERR, "Lock timer expired at: %s, elapsed: %s, lock age: %s ms", dt2str(now), elapsedTotal, lockFileAge);
            throw new IOException(fmt("Unable to grab Lock file (timeout: %s ms, elapsed: %s ms, lock age: %s ms)",
                timeout, elapsedTotal, lockFileAge));
        }

        if (logLevelLeast(DBG))
            log("Lock taken: %s %s", _lockFile, _lockFile.channel());

        try {
            try {
                // Check again after possible lock contention
                if (verifyExistingResourceFiles(deploymentPath)) {
                    log("Verified files after lock");
                    return;
                }
            } catch (Throwable e) {
                log("2nd Verify call threw");
                throw e;
            }

            log("Deploying to: %s", deploymentPath);
            lockFileWatchdogInit();

            try {
                deployResourcesInternal(deploymentPath);
            } catch (Throwable e) {
                log("Deployment threw");
                throw e;
            }

            // Update watchdog one last time
            lockFileWatchdogUpdate();
        } catch (Throwable e) {
            log("Verify/Deploy throw/rethrow");
            throw e;
        }

        // NOTE: LockFile may still exist and will be released later by the caller
        // finally{} clause that releases the lock is removed from here
    }


    private void loadAt(Path deploymentPath) throws IOException {

        if (null == _resources)
            throw argException("No resources to deploy");

        if (!deploymentPath.isAbsolute())
            throw new IllegalArgumentException(fmt("Deployment path can't be relative: %s", deploymentPath));

        if (!Files.exists(deploymentPath)) {
            Files.createDirectories(deploymentPath);
        } else {
            if (!Files.isDirectory(deploymentPath))
                throw new IOException(fmt("Deployment path is not a directory: %s", deploymentPath));
        }

        assert(_totalResourceLength >= 0);
        if (_retryTimeoutMs < 0)
            _retryTimeoutMs = _totalResourceLength / 4000 + 4000; // 4 MB/s + 4 sec

        // Update period fixed to be frequent enough to not cause _any_ concurrent processes to timeout
        // regardless of how big _their_ files are
        // It is guaranteed to be <= _retryTimeoutMs / 2
        _lockUpdatePeriodNs = 2000 * 1000000L;

        if (!OS.isWindows())
            _keepDllsLocked = true;

        log("RetryTimeout:%s, LockUpdatePeriodNs: %s%s",
            _retryTimeoutMs, _lockUpdatePeriodNs, _keepDllsLocked ? " KeepDllsLocked=1" : "");

        try {
            verifyOrDeployResources(deploymentPath);
            if (_shouldLoadDlls) {
                log("Loading dynamic libraries..");
                loadDynamicLibraries(deploymentPath);

                if (_keepDllsLocked)
                    keepDllFileHandles();
            }
        } catch (Throwable e) {
            if (_shouldLoadDlls) {
                // If some libs were already loaded before throwing, unload. All libs must be only loaded
                // from a single deployment path.
                log("UNloading libs..");
                unloadDynamicLibraries();
            }

            setFileLock(null);
            throw e;
        } finally {
            // Ensure all resource files are closed
            log("Dropping resource file locks..");
            disposeResourceFiles();
            // If lock file existed, update the last time before deletion
            if (null != _lockFile)
                lockFileWatchdogUpdate(true);

            setFileLock(null);
        }
    }


    private boolean tryLoadAt(final Path deploymentPath) {
        _lastUsedPath = deploymentPath.toString();

        try {
            loadAt(deploymentPath);
            _lastSuccessfulPath = _lastUsedPath;
            return true;
        }
        catch (IOException|SecurityException|UnsatisfiedLinkError e) {
            _lastDeploymentException = e;
            log("Failed to deploy to: %s", deploymentPath);
            return false;
        }
    }


    private void loadInternal() {

        if (null == _resourcePrefix)
            throw argException("Resource path is not set, use .from(resourcePathTemplate) to set");

        if (null == _deploymentPathTemplate)
            throw argException("Deployment path is not set, use .to(deploymentPathTemplate) to set");

        if (_alwaysOverwrite && _reusePartiallyDeployed)
            throw argException("AlwaysOverwrite=true is not compatible with ReusePartiallyDeployed=true");

        assert (null != _class);

        // TODO: check normalization
        String pathStr = applyPathTemplate(_deploymentPathTemplate, _class);
        Path path = Paths.get(pathStr);

        ArrayList<Path> paths = new ArrayList<>();
        // If user did not specify his preferred deployment root path, prepare our own

        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));

        if (!path.isAbsolute()) {
            String[] rootPaths = {
                OS.isWindows() ? getenv("ProgramData") : null
                , OS.isWindows() ? getenv("AppData") : null
                , OS.isOsx() ? System.getProperty("user.home") + "/Library/Application Support" : null
                , OS.isLinux() ? System.getProperty("user.home") + "/.local/share" : null
            };

            for (String rootPath : rootPaths) {
                if (null != rootPath && 0 != rootPath.length()) {
                    Path root = Paths.get(rootPath);
                    if (root.isAbsolute() && Files.exists(root))
                        paths.add(root.resolve(path));
                }
            }

            // Temp dir and random subfolders in the temp dir are always enabled
            Path fallbackPath = tempDir.resolve(path);
            Path fallbackPath2 = fallbackPath.resolve(nextRandomDirString());
            paths.add(fallbackPath);
            paths.add(fallbackPath2);

            FileJanitor.addCleanupPath(fallbackPath, false, RANDOM_DIR_REGEX);
        } else {
            paths.add(path);
            if (_addRandomFallbackSubDirectory) {
                paths.add(path.resolve(nextRandomDirString()));

                FileJanitor.addCleanupPath(path, false, RANDOM_DIR_REGEX);
            }
        }

        // TODO: verify
        FileJanitor.registerForCleanupOnExit();

        if (LogLevel <= DBG) {
            log("Deployment paths:");
            for (Path p : paths)
                log("%s", p);
        }
        try {
            try {
                if (null == _resources)
                    listResources();
            } catch (Throwable e) {
                throw new RuntimeException(fmt("Failed to list/scan resources at: %s", _resourcePathTemplate), e);
            }

            for (Path p : paths) {
                if (tryLoadAt(p))
                    return;
            }

            // Failure!
            throw new RuntimeException(fmt("Failed to deploy&load native resources using path: %s", _lastUsedPath), _lastDeploymentException);
        }
        finally {
            Closeable jfs = _jarFileSystem;
            _jarFileSystem = null;
            if (null != jfs) {
                try {
                    jfs.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Set resource path template and the class whose classloader will be used to load resources
     */
    private ResourceLoader fromInternal(Class clazz, String resourcePathTemplate) {

        // Set resource path and perform basic validation
        if (null == resourcePathTemplate)
            throw new NullPointerException("resourcePathTemplate");

        if (null != _resourcePrefix)
            throw argException("Resource path is already specified!");

        if (null != _class) {
            if (null != clazz && clazz != _class)
                throw new IllegalArgumentException(fmt("Parent class is already set: %s", _class));
        }
        else {
            _class = (null != clazz) ? clazz : this.getClass();
        }

        _resourcePathTemplate = resourcePathTemplate;

        /*
         * Expand path template, normalize it and verify
         */
        _resourcePath = _resourcePrefix = _resourceSuffix = null;
        String srcPath = applyBasicTemplate(resourcePathTemplate, _class);

        // NOTE: We are not using standard path manipulation APIs because they are platform-dependent

        // Perform basic check
        if (Pattern.matches("[\\s\\\\?]", srcPath))
            throw argException("Invalid characters detected in expanded resource path string: %s", srcPath);

        // "Normalize" the resource path
        // Note that we always consider the path to be absolute and convert it into such
        srcPath = ("/" + srcPath).replaceAll("\\/+", "/");
        int lastPathSeparator = srcPath.lastIndexOf('/');
        if (lastPathSeparator < 0) {
            // Path is not present, just a single filename in the root
            if (srcPath.lastIndexOf("*") >=0)
                throw argException("'*' not supported without path component in: %s", srcPath);

            _resourcePath = srcPath;
            return this;
        }

        // Path is present, split
        String resourcePath = srcPath.substring(0, lastPathSeparator);
        // Split without discarding empty components. Without separator there will be 1 part
        // With separator at the end, 2nd part will == ""
        String[] parts = srcPath.substring(lastPathSeparator + 1).split("\\*", -1);
        assert (parts.length > 0);
        if (parts.length > 2)
            throw argException("Resource path must contain at most one * character: %s", srcPath);

        _resourcePrefix = parts[0];
        if (parts.length == 2) {
            // May be null or "", null means direct filename
            _resourceSuffix = parts[1];
            _resourcePath = resourcePath;
        } else {
            _resourcePath = srcPath;
        }

        return this;
    }

    /**
     * Set deployment path template. Can be relative or absolute.
     * @param deploymentPathTemplate mandatory path template
     * @return This ResourceLoader instance
     */
    private ResourceLoader toInternal(String deploymentPathTemplate) {

        if (null == deploymentPathTemplate)
            throw new NullPointerException("deploymentPathTemplate is null");

        _deploymentPathTemplate = deploymentPathTemplate;
        return this;
    }

    /*
     * Public interface implementation
     */

    @Override
    public String getActualDeploymentPath() {
        return null != _lastSuccessfulPath ? _lastSuccessfulPath : _lastUsedPath;
    }

    @Override
    public String getActualResourcePath() {
        return null != _resourcePrefix ? fmt(null != _resourceSuffix ? "%s/%s*%s" : "%s", _resourcePath, _resourcePrefix, _resourceSuffix) : null;
    }

    @Override
    public ResourceLoaderDone unloadDlls() {

        if (null == _resources)
            throw new UnsupportedOperationException("No resources were loaded");

        unloadDynamicLibraries();
        return this;
    }

    @Override
    public ResourceLoaderInstance alwaysOverwrite(boolean alwaysOverwrite) {
        _alwaysOverwrite = alwaysOverwrite;
        return this;
    }

    @Override
    public ResourceLoaderInstance reusePartiallyDeployed(boolean reuseEnabled) {
        _reusePartiallyDeployed = reuseEnabled;
        return this;
    }

    @Override
    public ResourceLoaderInstance shouldLoadDlls(boolean shouldLoadDlls) {
        _shouldLoadDlls = shouldLoadDlls;
        return this;
    }

    @Override
    public ResourceLoaderInstance tryRandomFallbackSubDirectory(boolean enable) {
        _addRandomFallbackSubDirectory = enable;
        return this;
    }

    @Override
    public ResourceLoaderInstance addDllSuffix(String libraryNameSuffix) {

        if (null == libraryNameSuffix || libraryNameSuffix.equals(""))
            throw argException("libraryNameSuffix should not be empty");

        _libraryNameSuffix = libraryNameSuffix;
        return this;
    }

    @Override
    public ResourceLoaderInstance retryTimeout(int millis) {
        _retryTimeoutMs = millis;
        return this;
    }

    @Override
    public ResourceLoaderDone load() {
        // TODO: May want to do something with these exceptions later
        loadInternal();
        return this;
    }


    /**
     * Set resource path template. Resources matching the template will be deployed to the destination dir.
     * <p>The template may contain the asterisk character <code>*</code>, denoting variable part of the resource name that
     * will become the actual filename.
     * <p> More advanced search masks or Regular Expressions are not supported.
     * <p> All underscore ('_') characters in the filename will be replaced with '.' character.
     * Resources, whose names were ending with '.zst'/'_zst' will be decompressed by ZStandard with '.zst' suffix removed
     * <p>
     * The following variables will be substituted:
     * <ul>
     * <li>$(OS) =&gt; 'Windows' | 'Linux' | 'OSX' - name of the current OS platform</li>
     * <li>$(VERSION) =&gt; current package version</li>
     * <li>$(ARCH) =&gt; '32' | '64' - pointer size of the current architecture</li>
     * <li>$(DLLEXT) =&gt; 'dll' | 'so' | 'dylib' - dynamic library file extension for the current OS platform</li>
     * </ul>
     *
     * @param resourcePathTemplate Resource path template. Describes the source location of the deployed resource set.
     * @return Instance of partially initialized ResourceLoader, call .to() to set deployment path
     */
    public static From from(String resourcePathTemplate) {
        return from(null, resourcePathTemplate);
    }

    /**
     * Set resource path template. Resources matching the template will be deployed to the destination dir.
     * <p>The template may contain the asterisk character <code>*</code>, denoting variable part of the resource name that
     * will become the actual filename.
     * <p> More advanced search masks or Regular Expressions are not supported.
     * <p> All underscore ('_') characters in the filename will be replaced with '.' character.
     * Resources, whose names were ending with '.zst'/'_zst' will be decompressed by ZStandard with '.zst' suffix removed
     * <p>
     * The following variables will be substituted:
     * <ul>
     * <li>$(OS) =&gt; 'Windows' | 'Linux' | 'OSX' - name of the current OS platform</li>
     * <li>$(VERSION) =&gt; current package version</li>
     * <li>$(ARCH) =&gt; '32' | '64' - pointer size of the current architecture</li>
     * <li>$(DLLEXT) =&gt; 'dll' | 'so' | 'dylib' - dynamic library file extension for the current OS platform</li>
     * </ul>
     *
     * @param clazz class whose classloader will be used for loading the resources.
     * @param resourcePathTemplate Resource path template. Describes the source location of the deployed resource set.
     * @return Builder class asking for the deployment path template, call .to() to set deployment path
     */
    public static From from(Class clazz, String resourcePathTemplate) {
        return new From(newInstance().fromInternal(clazz, resourcePathTemplate));
    }

    /**
     * Set deployment path (absolute or relative).
     * If a relative path is specified, will try it with several possible root path, until deployment succeeds.
     * @param deploymentPathTemplate Deployment path template
     * @return Builder class asking for the resource path template, call .from() to set resource path
     */
    public static To to(String deploymentPathTemplate) {
        return new To(newInstance().toInternal(deploymentPathTemplate));
    }
}
