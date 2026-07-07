package com.mytube.apk;

final class PlaybackData {
    final String title;
    final String uploader;
    final String mediaUrl;
    final boolean manifest;

    PlaybackData(String title, String uploader, String mediaUrl, boolean manifest) {
        this.title = title;
        this.uploader = uploader;
        this.mediaUrl = mediaUrl;
        this.manifest = manifest;
    }
}
