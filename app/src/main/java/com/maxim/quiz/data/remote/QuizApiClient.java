package com.maxim.quiz.data.remote;

import com.maxim.quiz.BuildConfig;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

public final class QuizApiClient {

    private static final String BASE_URL = BuildConfig.QUIZ_API_BASE_URL;
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2200, TimeUnit.MILLISECONDS)
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
