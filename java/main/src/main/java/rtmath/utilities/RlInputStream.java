package rtmath.utilities;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

class RlInputStream extends InputStream {
    private final int _size;
    protected final Closeable _in;

    public RlInputStream(Closeable in, int size) {
        _in = in;
        _size = size;
    }

    /**
     * Returns file size
     * @return file length as 32-bit integer
     */
    public int size() {
        return _size;
    }

    @Override
    public int read() throws IOException {
        throw new UnsupportedOperationException();
    }

    public int read(ByteBuffer buffer) throws IOException {
        int pos;
        int numRead = ((InputStream)_in).read(buffer.array(), pos = buffer.position(), buffer.remaining());
        assert (numRead <= buffer.remaining());
        if (numRead > 0)
            buffer.position(pos + numRead);

        return numRead;
    }

    @Override
    public void close() throws IOException {
        _in.close();
    }

    public static RlInputStream wrap(FileChannel fc, int size) {
        return new RlChannelStream(fc, size);
    }

    public static RlInputStream wrap(InputStream inputStream, int size) {
        return new RlInputStream(inputStream, size);
    }

    private static class RlChannelStream extends RlInputStream {
        public RlChannelStream(FileChannel fc, int size) {
            super(fc, size);
        }

        @Override
        public int read(ByteBuffer b) throws IOException {
            return ((FileChannel)_in).read(b);
        }
    }
}
