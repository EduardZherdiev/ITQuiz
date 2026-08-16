package com.maxim.quiz;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.maxim.quiz.data.QuizRepository;
import com.maxim.quiz.data.local.model.TopicCardRow;

import java.util.ArrayList;
import java.util.List;

public class TopicsViewModel extends AndroidViewModel {

    private final QuizRepository repository;
    private final String languageCode;
    private final MediatorLiveData<List<TopicModel>> topicModels = new MediatorLiveData<>();

    public TopicsViewModel(@NonNull Application application) {
        super(application);
        repository = QuizRepository.create(application);
        languageCode = QuizLanguage.current(application);
        // Publish the bundled catalog before Room/network callbacks arrive.
        topicModels.setValue(TopicCatalog.fallback(application));
        LiveData<List<TopicCardRow>> topicRows = repository.observeTopicCards(languageCode);
        topicModels.addSource(topicRows, rows -> topicModels.setValue(mapTopicRows(rows)));
    }

    public LiveData<List<TopicModel>> getTopics() {
        return topicModels;
    }

    public void refresh() {
        repository.syncBootstrapAsync(languageCode);
    }

    private List<TopicModel> mapTopicRows(List<TopicCardRow> rows) {
        List<TopicModel> models = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return TopicCatalog.fallback(getApplication());
        }
        for (TopicCardRow row : rows) {
            String code = row == null ? null : row.code;
            int drawable = TopicCatalog.resolveImage(code);
            // Abbreviations are stable product codes and are never translated.
            String abbr = code == null ? "" : code;
            String title = row != null && row.title != null ? row.title : "";
            String description = row != null && row.description != null ? row.description : "";
            String id = row != null && row.topicId != null ? row.topicId : "";
            // The bundled catalog is localized explicitly, so it also fixes
            // old English rows that may still be present in Room after a
            // language change. The server sends the same display content and
            // remains the source for question data.
            String bundledTitle = TopicCatalog.fallbackTitle(getApplication(), code);
            String bundledDescription = TopicCatalog.fallbackDescription(getApplication(), code);
            if (bundledTitle != null && !bundledTitle.isEmpty()) {
                title = bundledTitle;
            }
            if (bundledDescription != null && !bundledDescription.isEmpty()) {
                description = bundledDescription;
            }
            models.add(new TopicModel(id, drawable, abbr, title, description));
        }
        return models;
    }
}
