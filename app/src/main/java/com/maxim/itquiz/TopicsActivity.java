package com.maxim.itquiz;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import com.maxim.itquiz.data.QuizRepository;

public class TopicsActivity extends AppCompatActivity {

    private final List<TopicModel> topicModels = new ArrayList<>();
    private TopicsAdapter adapter;
    private TopicsViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_topics);
        setTitle(R.string.title_topics);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }


        RecyclerView recyclerView = findViewById(R.id.topicsRecyclerView);
        adapter = new TopicsAdapter(this, topicModels);
        recyclerView.setAdapter(adapter);
        viewModel = new ViewModelProvider(this).get(TopicsViewModel.class);
        viewModel.getTopics().observe(this, topics -> {
            topicModels.clear();
            if (topics != null) {
                topicModels.addAll(topics);
            }
            adapter.notifyDataSetChanged();
        });

        View loadingPanel = findViewById(R.id.topicsLoadingPanel);
        if (loadingPanel != null) {
            loadingPanel.setVisibility(View.GONE);
        }

        QuizRepository.create(this).syncBootstrapAsync(QuizLanguage.current(this));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

}
