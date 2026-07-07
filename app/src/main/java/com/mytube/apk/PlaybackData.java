package com.mytube.apk;

import java.util.Collections;
import java.util.List;

final class PlaybackData {
    final String title;
    final String uploader;
    final String mediaUrl;
    final boolean manifest;
    final long viewCount;      // -1 when unknown
    final String likeCount;    // "" when unknown
    final String uploadDate;   // "" when unknown
    final String description;  // "" when unknown
    final List<String> tags;

    PlaybackData(String title, String uploader, String mediaUrl, boolean manifest) {
        this(title, uploader, mediaUrl, manifest, -1, "", "", "", Collections.emptyList());
    }

    PlaybackData(String title, String uploader, String mediaUrl, boolean manifest,
                 long viewCount, String likeCount, String uploadDate, String description,
                 List<String> tags) {
        this.title = title;
        this.uploader = uploader;
        this.mediaUrl = mediaUrl;
        this.manifest = manifest;
        this.viewCount = viewCount;
        this.likeCount = likeCount == null ? "" : likeCount;
        this.uploadDate = uploadDate == null ? "" : uploadDate;
        this.description = description == null ? "" : description;
        this.tags = tags == null ? Collections.emptyList() : tags;
    }
}
