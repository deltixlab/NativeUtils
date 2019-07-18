package rtmath.utilities;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Simple wrapper for Jar filesystem. Necessary to allow unpacking from embedded jars.
class JarFs implements Closeable {
    private final URL _url;
    private ZipInputStream _jar;

    public JarFs(URL jarUrl) {
        _url = jarUrl;
    }

    private RlInputStream find(String name) throws IOException {
        ZipEntry entry;

        while (null != (entry = _jar.getNextEntry())) {
            if (name.equals(entry.getName()))
                return new RlZipInputStream(_jar, (int)entry.getSize());
        }

        return null;
    }

    RlInputStream get(String name) throws IOException {
        RlInputStream stream = null;

        if (null != _jar) {
            stream = find(name);
        }

        if (null == stream) {
            open();
            stream = find(name);
        }

        return stream;
    }

    private void open() throws IOException {
        close();
        _jar = new ZipInputStream(_url.openStream());
    }

    @Override
    public void close() throws IOException {
        if (null != _jar) {
            _jar.close();
            _jar = null;
        }
    }

    private class RlZipInputStream extends RlInputStream {
        public RlZipInputStream(ZipInputStream jar, int size) {
            super(jar, size);
        }

        @Override
        public void close() throws IOException {
            ((ZipInputStream)_in).closeEntry();
        }
    }
}

