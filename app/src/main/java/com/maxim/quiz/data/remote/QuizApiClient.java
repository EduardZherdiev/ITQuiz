package com.maxim.quiz.data.remote;

import com.maxim.quiz.BuildConfig;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public final class QuizApiClient {

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
    private static final QuizApiService SERVICE = new Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(HTTP_CLIENT)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuizApiService.class);

    private QuizApiClient() {
    }

    public static QuizApiService service() {
        return SERVICE;
    }
}
