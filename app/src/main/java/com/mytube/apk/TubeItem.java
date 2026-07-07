package com.mytube.apk;

final class TubeItem {
    final String title;
    final String subtitle;
    final String url;
    final String thumbnailUrl;
    final boolean playable;
    final boolean shortForm;

    TubeItem(String title, String subtitle, String url, String thumbnailUrl, boolean playable, boolean shortForm) {
        this.title = title;
        this.subtitle = subtitle;
        this.url = url;
        this.thumbnailUrl = thumbnailUrl;
        this.playable = playable;
        this.shortForm = shortForm;
    }
}
