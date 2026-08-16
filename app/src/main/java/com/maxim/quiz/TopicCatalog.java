package com.maxim.quiz;

import android.content.Context;
import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small bundled catalog used while the server bootstrap is still downloading.
 * The server remains the source of questions and ownership; this only makes
 * navigation screens render immediately on a cold start.
 */
public final class TopicCatalog {

    private TopicCatalog() {
    }

    public static final class Definition {
        public final String id;
        public final String code;
        public final int image;
        public final int titleRes;
        public final int descriptionRes;

        Definition(String id, String code, int image, int titleRes, int descriptionRes) {
            this.id = id;
            this.code = code;
            this.image = image;
            this.titleRes = titleRes;
            this.descriptionRes = descriptionRes;
        }
    }

    private static final Definition[] DEFINITIONS = {
            new Definition("1", "AI", R.drawable.ai, R.string.topic_ai_title, R.string.topic_ai_description),
            new Definition("2", "ALL", R.drawable.all, R.string.topic_all_title, R.string.topic_all_description),
            new Definition("3", "ALGO", R.drawable.algorithms, R.string.topic_algo_title, R.string.topic_algo_description),
            new Definition("4", "ARCH", R.drawable.arch, R.string.topic_arch_title, R.string.topic_arch_description),
            new Definition("5", "C", R.drawable.c, R.string.topic_c_title, R.string.topic_c_description),
            new Definition("6", "CPP", R.drawable.cpp, R.string.topic_cpp_title, R.string.topic_cpp_description),
            new Definition("7", "CSHARP", R.drawable.csh, R.string.topic_csharp_title, R.string.topic_csharp_description),
            new Definition("8", "DB", R.drawable.db, R.string.topic_db_title, R.string.topic_db_description),
            new Definition("9", "JAVA", R.drawable.java, R.string.topic_java_title, R.string.topic_java_description),
            new Definition("10", "JS", R.drawable.js, R.string.topic_js_title, R.string.topic_js_description),
            new Definition("11", "MATH", R.drawable.math, R.string.topic_math_title, R.string.topic_math_description),
            new Definition("12", "NET", R.drawable.networks, R.string.topic_net_title, R.string.topic_net_description),
            new Definition("13", "OS", R.drawable.os, R.string.topic_os_title, R.string.topic_os_description),
            new Definition("14", "PY", R.drawable.py, R.string.topic_py_title, R.string.topic_py_description),
            new Definition("15", "SQL", R.drawable.sql, R.string.topic_sql_title, R.string.topic_sql_description)
    };

    public static Definition find(String code) {
        if (code == null) {
            return null;
        }
        for (Definition definition : DEFINITIONS) {
            if (definition.code.equalsIgnoreCase(code)) {
                return definition;
            }
        }
        return null;
    }

    public static List<TopicModel> fallback(Context context) {
        List<TopicModel> result = new ArrayList<>();
        Context localizedContext = localizedContext(context);
        for (Definition definition : DEFINITIONS) {
            result.add(new TopicModel(
                    definition.id,
                    definition.image,
                    definition.code,
                    localizedContext.getString(definition.titleRes),
                    localizedContext.getString(definition.descriptionRes)
            ));
        }
        return result;
    }

    public static int resolveImage(String code) {
        Definition definition = find(code);
        return definition == null ? R.drawable.all : definition.image;
    }

    public static String fallbackTitle(Context context, String code) {
        Definition definition = find(code);
        return definition == null ? code : localizedContext(context).getString(definition.titleRes);
    }

    public static String fallbackDescription(Context context, String code) {
        Definition definition = find(code);
        return definition == null ? "" : localizedContext(context).getString(definition.descriptionRes);
    }

    private static Context localizedContext(Context context) {
        String language = QuizLanguage.current(context);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(language));
        return context.createConfigurationContext(configuration);
    }
}
