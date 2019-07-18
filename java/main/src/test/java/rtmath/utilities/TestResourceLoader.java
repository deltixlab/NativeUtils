package rtmath.utilities;
import org.junit.Assert;
import org.junit.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static java.lang.System.currentTimeMillis;
import static java.lang.System.lineSeparator;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.Assert.assertTrue;
import static rtmath.utilities.ResourceLoaderUtils.log;

public class TestResourceLoader {
    private static final Object lock = new Object();
    static final String srcBase = "resources/$(OS)/$(ARCH)/";
    static final String src = srcBase + "*";
    static final String dst = ".rtmath/SampleDll/Java/$(VERSION)/$(ARCH)";
    private static final int TEST_CLASSPATH = 2;
    private static final int TEST_FATJAR = 1;
    private static final int TEST_JARS = 0;

    private Path deploymentPath;

    private void cleanup() {
        synchronized (this.lock) {
            if (null == deploymentPath) {
                deploymentPath = deployOnly(src, dst);
            } else {
                if (!Files.exists(deploymentPath))
                    return;
            }

            tryClean(deploymentPath);
        }
    }

    private static String deploymentPath(String testName) {
        return "$(TEMP)/.rtmath/SampleDll/Java/$(VERSION)/" + testName + "/$(ARCH)/";
    }

    @Test
    public void testDeployAndCleanupAbsPathFilename() {
        Path path = deployOnly(srcBase + "dummy1.txt.zst", deploymentPath("deploy_Test1fn"));

        Assert.assertTrue(Files.exists(path.resolve("dummy1.txt")));
        Assert.assertFalse(Files.exists(path.resolve("dummy3.txt")));
        tryClean(path);
    }

    @Test
    public void testDeployAndCleanupAbsPath() {
        Path path = deployOnly(src, deploymentPath("deploy_Test1"));

        Assert.assertTrue(Files.exists(path.resolve("dummy1.txt")));
        Assert.assertTrue(Files.exists(path.resolve("dummy3.txt")));
        tryClean(path);
    }

    @Test
    public void testDeployAndCleanupAbsPath2() {
        Path path = deployOnly(src, deploymentPath("deploy_Test1"));

        tryClean(path);
        Path parent = path.getParent();
        tryClean(parent);
    }

    @Test
    public void testDeployAndCleanupAbsPathCopy() throws IOException {
        Path path = deployOnly(src, deploymentPath("deploy_Test2"));
        Path path1 = path.getParent().getParent();
        Path path2 = path1.resolve( "deploy_Test2Copy");

        clean(path2.resolve(path.getFileName()));
        clean(path2);

        copy(path.toFile(), path2.toFile());
        tryClean(path2);
        copy(path.getParent().toFile(), path2.toFile());
        tryClean(path2, true, "..");
        clean(path);
        clean(path.getParent());

    }


    String prepareDstPath(String dst) {
        Path path = deployOnly(src, dst);

        clean(path);
        // Will be cleaned automatically after exit
        FileJanitor.addCleanupPath(path);
        FileJanitor.addCleanupPath(path.getParent());
        return dst;
    }

    @Test
    public void testRunClasspathProcess() {
        String dst = prepareDstPath(deploymentPath("testRunClasspathProcess"));
        verify(readAllLines(runTestProcess(TEST_CLASSPATH, src, dst)));
    }

    @Test
    public void testRun4ClasspathProcesses() {
        String dst = prepareDstPath(deploymentPath("testRun4ClasspathProcesses"));
        testManyProcesses(TEST_CLASSPATH, src, dst, 4);
    }

    @Test
    public void testRun64ClasspathProcesses() {
        String dst = prepareDstPath(deploymentPath("testRun64ClasspathProcesses"));
        testManyProcesses(TEST_CLASSPATH, src, dst, 64);
    }

    @Test
    public void testRunJarProcess() {

        String dst = prepareDstPath(deploymentPath("testRunJarProcess"));
        verify(readAllLines(runTestProcess(TEST_JARS, src, dst)));
    }

    @Test
    public void testRun4JarProcesses() {
        String dst = prepareDstPath(deploymentPath("testRun4JarProcesses"));
        testManyProcesses(TEST_JARS, src, dst, 4);
    }

    @Test
    public void testRun32JarProcesses() {
        String dst = prepareDstPath(deploymentPath("testRun32JarProcesses"));
        testManyProcesses(TEST_JARS, src, dst, 32);
    }

    @Test
    public void testRunFatJarProcess() {
        String dst = prepareDstPath(deploymentPath("testRunFatJarProcess"));
        verify(readAllLines(runTestProcess(TEST_FATJAR, src , dst)));
    }

    @Test
    public void testRun4FatJarProcesses() {
        String dst = prepareDstPath(deploymentPath("testRun4FatJarProcesses"));
        testManyProcesses(TEST_FATJAR, src, dst, 4);
    }

    @Test
    public void testRun32FatJarProcesses() {
        String dst = prepareDstPath(deploymentPath("testRun32FatJarProcesses"));
        testManyProcesses(TEST_FATJAR, src, dst, 32);
    }

    @Test
    public void testBasicUsage() {

        cleanup();
        ResourceLoaderDone rl = ResourceLoader
            .from(this.src)
            .to(this.dst)
            .load();

        String path = rl.getActualDeploymentPath();
    }

    /**
     * Utils (Test-specific)
     */

    private static Path deployOnly(String from, String to) {
        ResourceLoaderDone rl = ResourceLoader
            .from(from)
            .to(to)
            .shouldLoadDlls(false)
            .load();

        Path deploymentPath = Paths.get(rl.getActualDeploymentPath());
        if (!Files.exists(deploymentPath))
            throw new RuntimeException("Deployment path not found: " + deploymentPath);

        if (!Files.isDirectory(deploymentPath))
            throw new RuntimeException("Deployment path is not a directory: " + deploymentPath);

        return deploymentPath;
    }

    private static void clean(Path path) {
        try { tryClean(path, true, ".."); } catch (Exception e) {}
    }

    private static void clean(String path) { clean(Paths.get(path)); }

    static void tryClean(Path path) {
        tryClean(path, true, null);
    }


    static void tryClean(Path path,  boolean cleanDir, String subDirRegEx) {
        if (!FileJanitor.tryCleanup(path.toString(), cleanDir, subDirRegEx))
            throw new RuntimeException("Failed to clean deployment path: " + path);

        if (Files.exists(path))
            throw new RuntimeException("Failed to clean deployment path(still exists): " + path);
    }

    private static void verify(String []lines) {
        if (!outputOk(lines))
            throw new RuntimeException("Test process thrown an exception: " + join(lines));
    }

    private static boolean outputOk(String line) {
        return null != line && line.startsWith("OK!: ");
    }

    private static boolean outputOk(String[] lines) {
        return lines.length > 0 && outputOk(lines[lines.length - 1]);
    }

    private static BufferedReader runTestProcess(String... args) throws IOException {
        return runTestProcess(TEST_CLASSPATH, args);
    }

    private static BufferedReader runTestProcess(int testMode, String... args) {

        //Path localPath = Paths.get("").toAbsolutePath();
        List<String> cmd = new ArrayList<String>();
        char separator = File.pathSeparatorChar;
        cmd.add("java");

        switch (testMode) {
            case TEST_JARS:
                // NOTE: the main jar is referenced in the manifest
                cmd.add("-jar");
                cmd.add("build/libs/tests.jar");
                break;

            case TEST_FATJAR:
                cmd.add("-jar");
                cmd.add("build/libs/testsFat.jar");
                break;

            case TEST_CLASSPATH:
                cmd.add("-classpath");
                cmd.add("build/classes/java/test" + separator + "build/resources/test"
                    + separator + "build/classes/java/main");
                cmd.add("rtmath.utilities.TestProgram");
                break;
        }

        for (String s : args) {
            cmd.add(s);
        }

        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return new BufferedReader(new InputStreamReader(process.getInputStream()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static BufferedReader[] runManyTestProcesses(int mode, String src, String dst, int n) {

        BufferedReader[] many = new BufferedReader[n];
        for (int i = 0; i < n; i++)
            many[i] = runTestProcess(mode, src, dst);

        return many;
    }


    private static void testManyProcesses(int mode, String src, String dst, int numProcesses) {

        BufferedReader[] pr = runManyTestProcesses(mode, src, dst, numProcesses);
        StringBuilder[] sb = new StringBuilder[numProcesses];
        String[] last = new String[numProcesses];
        long start = currentTimeMillis();
        int done = 0, fail = 0;

        while (done < numProcesses) {
            for (int i = 0; i < numProcesses; i++) {
                BufferedReader p = pr[i];

                if (null == sb[i]) {
                    sb[i] = new StringBuilder();
                }

                if (null != p) {
                    String line = null;

                    try {
                        line = p.readLine();
                    }
                    catch (IOException e) {
                        line = " Stream read error: " + e.getMessage();
                        e.printStackTrace();
                    }

                    if (null == line) {
                        ++done;
                        try {
                            p.close();
                        } catch (IOException e) {}

                        pr[i] = null;

                        if (!outputOk(last[i]))
                            ++fail;
                    } else {
                        sb[i].append(line).append('\n');
                        last[i] = line;
                    }
                }
            }
        }

        if (fail != 0) {
            StringBuilder s = new StringBuilder("Test for multiple processes failed!");

            for (int i = 0; i < numProcesses; i++) {
                s.append("\n================= Process ").append(i).append(" =================\n");
                s.append(sb[i].toString());
            }

            throw new RuntimeException(s.toString());
        }
    }


    /**
     * Utils (Generic)
     */

    public static void copy(File sourceLocation, File targetLocation) throws IOException {
        if (sourceLocation.isDirectory()) {
            copyDir(sourceLocation, targetLocation);
        } else {
            Files.copy(sourceLocation.toPath(), targetLocation.toPath(), REPLACE_EXISTING);
        }
    }


    private static void copyDir(File source, File target) throws IOException {

        if (!target.exists()) {
            target.mkdir();
        }

        for (String f : source.list()) {
            copy(new File(source, f), new File(target, f));
        }
    }

    /**
     * Reads all lines from a Bufferred Reader, returning last
     * @param br Buffered reader to real lines from
     * @return Array of lines
     * @throws IOException
     */
    private static String[] readAllLines(BufferedReader br) {
        List<String> l = new ArrayList<String>();
        String line;

        try {
            while ((line = br.readLine()) != null) {
                l.add(line);
            }
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
        finally {
            try {
                br.close();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }

        return l.toArray(new String[0]);
    }


    private static String join(String[] lines) {
        StringBuilder sb = new StringBuilder();

        for(String l : lines)
            sb.append(l).append('\n');

        return sb.toString();
    }
}
