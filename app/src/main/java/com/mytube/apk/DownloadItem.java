package com.mytube.apk;

// A completed offline download: metadata plus the local uri of the saved file.
final class DownloadItem {
    final String id;            // YouTube video id (also used to de-dupe)
    final String title;
    final String uploader;
    final String uri;           // "file:///..." or "content://..." of the saved video
    final String thumbnailUrl;
    final String quality;
    final String searchText;    // title/uploader/tags/description snapshot for offline search

    DownloadItem(String id, String title, String uploader, String uri,
                 String thumbnailUrl, String quality) {
        this(id, title, uploader, uri, thumbnailUrl, quality, "");
    }

    DownloadItem(String id, String title, String uploader, String uri,
                 String thumbnailUrl, String quality, String searchText) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.uploader = uploader == null ? "" : uploader;
        this.uri = uri;
        this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        this.quality = quality == null ? "" : quality;
        this.searchText = searchText == null ? "" : searchText;
    }
}
