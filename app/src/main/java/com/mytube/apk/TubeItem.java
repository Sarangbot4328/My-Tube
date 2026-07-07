package com.mytube.apk;

final class TubeItem {
    final String title;
    final String subtitle;
    final String url;
    final String thumbnailUrl;
    final boolean playable;
    final boolean shortForm;
    final long publishedAgeSeconds;

    TubeItem(String title, String subtitle, String url, String thumbnailUrl, boolean playable, boolean shortForm) {
        this(title, subtitle, url, thumbnailUrl, playable, shortForm, Long.MAX_VALUE);
    }

    TubeItem(String title, String subtitle, String url, String thumbnailUrl,
             boolean playable, boolean shortForm, long publishedAgeSeconds) {
        this.title = title;
        this.subtitle = subtitle;
        this.url = url;
        this.thumbnailUrl = thumbnailUrl;
        this.playable = playable;
        this.shortForm = shortForm;
        this.publishedAgeSeconds = publishedAgeSeconds;
    }
}
