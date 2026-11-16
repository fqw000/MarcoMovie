package com.pilipala.marcomovie;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Media3 {@link DataSource} wrapper that filters HLS media playlists and blocks known ad
 * hosts/URLs. It sits outside {@code ChunkedProxyDataSource} in the data-source chain, so it sees a
 * logically continuous stream while the chunked proxy handling remains transparent below it.
 *
 * <p>Playlist detection is conservative: requests are only buffered and filtered when the URI or
 * {@code Content-Type} hints at a playlist, or when a short peek of the response starts with the
 * {@code #EXTM3U} magic. All other responses stream through unchanged, so large media segments are
 * never buffered in memory.
 */
final class AdFilterDataSource implements DataSource {
    private static final int PEEK_BYTES = 1024;

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory innerFactory;
        private final AdFilterRules rules;
        private final AdFilterStats stats;

        Factory(DataSource.Factory innerFactory, AdFilterRules rules, AdFilterStats stats) {
            this.innerFactory = innerFactory;
            this.rules = rules;
            this.stats = stats;
        }

        @Override
        public DataSource createDataSource() {
            return new AdFilterDataSource(innerFactory.createDataSource(), rules, stats);
        }
    }

    private final DataSource inner;
    private final AdFilterRules rules;
    private final AdFilterStats stats;

    private InputStream buffered;
    private boolean innerOpen;
    private Uri lastUri;
    private Map<String, List<String>> lastHeaders = Collections.emptyMap();

    AdFilterDataSource(DataSource inner, AdFilterRules rules, AdFilterStats stats) {
        this.inner = inner;
        this.rules = rules;
        this.stats = stats;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        inner.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        closeInnerQuietly();
        buffered = null;
        lastUri = spec.uri;
        lastHeaders = Collections.emptyMap();

        Uri uri = spec.uri;
        if (uri != null
                && (rules.isBlockedUrl(uri.toString())
                        || (uri.getHost() != null && rules.isBlockedHost(uri.getHost())))) {
            throw new IOException("Blocked by ad filter: " + uri);
        }

        long length = inner.open(spec);
        innerOpen = true;
        if (inner.getUri() != null) lastUri = inner.getUri();
        lastHeaders = inner.getResponseHeaders();

        if (!looksLikePlaylistByHint(uri, lastHeaders)) {
            byte[] head = readUpTo(inner, PEEK_BYTES);
            if (head.length == 0 || !isExtM3uPrefix(head)) {
                // Not a playlist: expose the peeked bytes followed by the remaining inner stream.
                buffered =
                        new SequenceInputStream(
                                new ByteArrayInputStream(head), new DataSourceInputStream(inner));
                return length;
            }
            byte[] body = readRemaining(inner, head);
            return serveFiltered(body, uri);
        }

        byte[] body = readAll(inner);
        return serveFiltered(body, uri);
    }

    private long serveFiltered(byte[] body, Uri uri) {
        String text = new String(body, StandardCharsets.UTF_8);
        if (!text.startsWith("#EXTM3U")) {
            buffered = new ByteArrayInputStream(body);
            return body.length;
        }
        String host = uri == null ? null : uri.getHost();
        M3u8AdFilter.Result result = M3u8AdFilter.filter(text, host, rules);
        if (result.removedSegments > 0) {
            stats.record(result.removedSegments);
        }
        byte[] filtered = result.text.getBytes(StandardCharsets.UTF_8);
        buffered = new ByteArrayInputStream(filtered);
        return filtered.length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (buffered != null) return buffered.read(buffer, offset, length);
        return inner.read(buffer, offset, length);
    }

    @Override
    public Uri getUri() {
        Uri active = innerOpen ? inner.getUri() : null;
        return active != null ? active : lastUri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return innerOpen ? inner.getResponseHeaders() : lastHeaders;
    }

    @Override
    public void close() throws IOException {
        buffered = null;
        closeInner();
    }

    private static boolean looksLikePlaylistByHint(Uri uri, Map<String, List<String>> headers) {
        if (uri != null) {
            String path = uri.getPath();
            if (path != null && path.endsWith(".m3u8")) return true;
            String query = uri.getQuery();
            if (query != null && query.contains(".m3u8")) return true;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!"content-type".equalsIgnoreCase(entry.getKey())) continue;
            for (String value : entry.getValue()) {
                if (value != null && value.toLowerCase(Locale.ROOT).contains("mpegurl"))
                    return true;
            }
        }
        return false;
    }

    private static boolean isExtM3uPrefix(byte[] data) {
        int i = 0;
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < data.length
                && (data[i] == ' ' || data[i] == '\t' || data[i] == '\r' || data[i] == '\n')) {
            i++;
        }
        byte[] magic = "#EXTM3U".getBytes(StandardCharsets.UTF_8);
        if (data.length - i < magic.length) return false;
        for (int j = 0; j < magic.length; j++) {
            if (data[i + j] != magic[j]) return false;
        }
        return true;
    }

    private static byte[] readUpTo(DataSource source, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(4096, maxBytes)];
        int remaining = maxBytes;
        while (remaining > 0) {
            int toRead = Math.min(buffer.length, remaining);
            int n = source.read(buffer, 0, toRead);
            if (n == C.RESULT_END_OF_INPUT) break;
            if (n == 0) continue;
            out.write(buffer, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    private static byte[] readRemaining(DataSource source, byte[] head) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(head, 0, head.length);
        byte[] buffer = new byte[16384];
        int n;
        while ((n = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
            if (n > 0) out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readAll(DataSource source) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
            if (n > 0) out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private void closeInnerQuietly() {
        if (innerOpen) {
            innerOpen = false;
            try {
                inner.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeInner() throws IOException {
        if (innerOpen) {
            innerOpen = false;
            inner.close();
        }
    }

    private static final class DataSourceInputStream extends InputStream {
        private final DataSource source;

        DataSourceInputStream(DataSource source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = source.read(buffer, offset, length);
            return n == C.RESULT_END_OF_INPUT ? -1 : n;
        }

        @Override
        public void close() throws IOException {
            // The owning AdFilterDataSource closes the wrapped source.
        }
    }
}
