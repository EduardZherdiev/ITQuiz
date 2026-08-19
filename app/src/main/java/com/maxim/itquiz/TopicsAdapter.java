package com.maxim.itquiz;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class TopicsAdapter extends RecyclerView.Adapter<TopicsAdapter.ViewHolder> {
    private final Context context;
    private final List<TopicModel> topicModels;

    public TopicsAdapter(Context context, List<TopicModel> topicModels) {
        this.context = context;
        this.topicModels = topicModels;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.topics_recycler_view_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TopicModel topic = topicModels.get(position);
        holder.imageView.setImageResource(topic.getImage());
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(v.getContext(), GameModeActivity.class);
            intent.putExtra(GameModeActivity.EXTRA_TOPIC_ID, topic.getId());
            intent.putExtra(GameModeActivity.EXTRA_TOPIC_NAME, topic.getName());
            intent.putExtra(GameModeActivity.EXTRA_TOPIC_DESCRIPTION, topic.getDescription());
            intent.putExtra(GameModeActivity.EXTRA_TOPIC_ABBR, topic.getAbbr());
            v.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return topicModels.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView imageView;

        ViewHolder(View itemView) {
            super(itemView);
            this.imageView = itemView.findViewById(R.id.topicImageView);
        }
    }
}
