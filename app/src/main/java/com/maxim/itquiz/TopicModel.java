package com.maxim.itquiz;

public class TopicModel {
    private final String id;
    private final int image;
    private final String abbr;
    private final String name;
    private final String description;

    public TopicModel(String id, int image, String abbr, String name) {
        this(id, image, abbr, name, "");
    }

    public TopicModel(String id, int image, String abbr, String name, String description) {
        this.id = id;
        this.image = image;
        this.abbr = abbr;
        this.name = name;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public int getImage() {
        return image;
    }

    public String getAbbr() {
        return abbr;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}
