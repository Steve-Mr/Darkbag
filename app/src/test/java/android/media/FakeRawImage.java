package android.media;

import android.graphics.Rect;
import java.nio.ByteBuffer;

public class FakeRawImage extends Image {
    private final int width;
    private final int height;
    private final long timestamp;
    private final ByteBuffer buffer;

    public FakeRawImage(int width, int height, long timestamp, ByteBuffer buffer) {
        this.width = width;
        this.height = height;
        this.timestamp = timestamp;
        this.buffer = buffer;
    }

    @Override
    public int getFormat() {
        return 0x20; // RAW_SENSOR
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public Plane[] getPlanes() {
        return new Plane[] {
            new FakePlane(buffer, width * 2)
        };
    }

    @Override
    public void close() {}

    @Override
    public void setTimestamp(long timestamp) {}

    @Override
    public Rect getCropRect() {
        return new Rect(0, 0, width, height);
    }

    @Override
    public void setCropRect(Rect cropRect) {}

    public static class FakePlane extends Plane {
        private final ByteBuffer buffer;
        private final int rowStride;

        public FakePlane(ByteBuffer buffer, int rowStride) {
            this.buffer = buffer;
            this.rowStride = rowStride;
        }

        @Override
        public int getRowStride() {
            return rowStride;
        }

        @Override
        public int getPixelStride() {
            return 2;
        }

        @Override
        public ByteBuffer getBuffer() {
            return buffer;
        }
    }
}
