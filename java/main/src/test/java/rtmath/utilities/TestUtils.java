package rtmath.utilities;

import org.junit.Assert;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static rtmath.utilities.FileJanitor.*;

public class TestUtils {
    final String tmp = System.getProperty("java.io.tmpdir");
    final Path tmpTestPath = Paths.get(tmp, "_rl_tests");

    static String getTags(String str, Map<String, String> tags) {

        tags.clear();
        return ResourceLoaderUtils.getTags(str, tags);
    }

    /**
     * Test tags implementation
     */
    @Test
    public void testTags() {

        Map<String, String> tags = new HashMap<>();

        Assert.assertEquals("12345", getTags("12[@]345", tags));
        Assert.assertEquals(1, tags.size());
        Assert.assertEquals("", tags.get(""));
        Assert.assertEquals("12345", getTags("12[i@141]345", tags));
        Assert.assertEquals(1, tags.size());
        Assert.assertEquals("141", tags.get("i"));
        Assert.assertEquals("kernel32_dll_zst", getTags("kerne[i@141]l32_d[foo@b[*~ar]ll_zst", tags));
        Assert.assertEquals(2, tags.size());
        Assert.assertEquals("141", tags.get("i"));
        Assert.assertEquals("b[*~ar", tags.get("foo"));
        Assert.assertEquals("fo[[]]obar[]].so.1", getTags("fo[[]]ob[\\@454564523463&%^&$%!#$!$]ar[]].so.1", tags));
        Assert.assertEquals(1, tags.size());
        Assert.assertEquals("454564523463&%^&$%!#$!$", tags.get("\\"));
    }

    @Test
    public void testLockFileOk() throws IOException {

        Path path = tmpTestPath.resolve("testLockFileOk");
        Files.createDirectories(path);
        try (LockFile lf = tryCreateLockFile(path)) {
        }
    }

    /**
     * Test that overlapping file locks fail
     * @throws IOException
     */
    @Test
    public void testLockFileFail() throws IOException {

        Path path = tmpTestPath.resolve("testLockFileFail");
        Files.createDirectories(path);
        Files.createDirectories(tmpTestPath);
        try (LockFile lf = tryCreateLockFile(path)) {
            try (LockFile lf2 = tryCreateLockFile(path)) {
                Assert.assertNull(lf2);
            }
        }
    }
}
