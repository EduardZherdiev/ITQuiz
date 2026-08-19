package com.maxim.itquiz.data.remote;

import com.maxim.itquiz.BuildConfig;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public final class QuizApiClient {

    private static final long FALLBACK_BOOTSTRAP_TOTAL_BYTES = 1024L * 1024L;

    public interface BootstrapProgressListener {
        void onProgress(String requestKey, long bytesRead, long contentLength);
    }

    private static volatile BootstrapProgressListener bootstrapProgressListener;

    private static final String BASE_URL = BuildConfig.QUIZ_API_BASE_URL;
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            // Some Vivo devices stall while waiting for HTTP/2 response
            // headers through the Render/Cloudflare edge.  HTTP/1.1 keeps
            // the same HTTPS connection security and avoids that stall.
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4500, TimeUnit.MILLISECONDS)
            .build();
    /** Catalog downloads may wake a sleeping Render instance; action calls stay fast. */
    private static final OkHttpClient BOOTSTRAP_HTTP_CLIENT = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // This is an application interceptor so progress is measured after
            // OkHttp has transparently decompressed a gzip response.
            .addInterceptor(chain -> {
                okhttp3.Response response = chain.proceed(chain.request());
                String path = chain.request().url().encodedPath();
                ResponseBody body = response.body();
                if (body == null || !path.endsWith("/api/v1/bootstrap")) {
                    return response;
                }
                BootstrapProgressListener listener = bootstrapProgressListener;
                if (listener == null) {
                    return response;
                }
                long declaredResponseLength = parsePositiveLong(
                        response.header("X-Bootstrap-Content-Length"),
                        body.contentLength()
                );
                final long responseLength = declaredResponseLength > 0
                        ? declaredResponseLength : FALLBACK_BOOTSTRAP_TOTAL_BYTES;
                return response.newBuilder()
                        .body(new ProgressResponseBody(
                                body,
                                chain.request().url().toString()
                                        + "|"
                                        + String.valueOf(chain.request().header("Accept-Language")),
                                listener,
                                responseLength
                        ))
                        .build();
            })
            .build();
    private static final QuizApiService SERVICE = new Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(HTTP_CLIENT)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuizApiService.class);
    private static final QuizApiService BOOTSTRAP_SERVICE = new Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(BOOTSTRAP_HTTP_CLIENT)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuizApiService.class);

    private QuizApiClient() {
    }

    public static void setBootstrapProgressListener(BootstrapProgressListener listener) {
        bootstrapProgressListener = listener;
    }

    public static QuizApiService service() {
        return SERVICE;
    }

    public static QuizApiService bootstrapService() {
        return BOOTSTRAP_SERVICE;
    }

    private static long parsePositiveLong(String value, long fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class ProgressResponseBody extends ResponseBody {
        private final ResponseBody delegate;
        private final String requestKey;
        private final BootstrapProgressListener listener;
        private final long progressContentLength;
        private BufferedSource bufferedSource;

        ProgressResponseBody(
                ResponseBody delegate,
                String requestKey,
                BootstrapProgressListener listener,
                long progressContentLength
        ) {
            this.delegate = delegate;
            this.requestKey = requestKey;
            this.listener = listener;
            this.progressContentLength = progressContentLength;
        }

        @Override
        public okhttp3.MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public BufferedSource source() {
            if (bufferedSource == null) {
                listener.onProgress(requestKey, 0L, progressContentLength);
                Source source = delegate.source();
                bufferedSource = Okio.buffer(new ForwardingSource(source) {
                    private long totalBytesRead;

                    @Override
                    public long read(Buffer sink, long byteCount) throws IOException {
                        long bytesRead = super.read(sink, byteCount);
                        if (bytesRead != -1) {
                            totalBytesRead += bytesRead;
                        }
                        listener.onProgress(
                                requestKey,
                                totalBytesRead,
                                Math.max(progressContentLength, totalBytesRead)
                        );
                        return bytesRead;
                    }
                });
            }
            return bufferedSource;
        }
    }
}
