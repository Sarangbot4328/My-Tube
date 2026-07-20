package com.mytube.apk;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Lets the user sign into YouTube inside a WebView so we can reuse that session
 * for NewPipe / InnerTube / media downloads (same idea as PC Edge cookies).
 */
public final class YoutubeLoginActivity extends Activity {
    private WebView webView;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieStore.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("YouTube 로그인");
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setTextSize(18);
        title.setPadding(dp(16), dp(14), dp(16), dp(4));
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("아래에서 Google/YouTube에 로그인한 뒤 «로그인 저장»을 누르세요.\n"
                + "PC 버전 Edge 쿠키와 같은 역할입니다. 비공개 기기에만 사용하세요.");
        help.setTextColor(Color.rgb(71, 85, 105));
        help.setTextSize(13);
        help.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(help);

        status = new TextView(this);
        status.setTextColor(Color.rgb(100, 116, 139));
        status.setTextSize(12);
        status.setPadding(dp(16), 0, dp(16), dp(8));
        status.setText("youtube.com 로딩 중…");
        root.addView(status);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(12), 0, dp(12), dp(8));

        Button save = new Button(this);
        save.setText("로그인 저장");
        save.setAllCaps(false);
        save.setOnClickListener(v -> saveCookies());
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button reload = new Button(this);
        reload.setText("새로고침");
        reload.setAllCaps(false);
        reload.setOnClickListener(v -> webView.reload());
        LinearLayout.LayoutParams reloadLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        reloadLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(reload, reloadLp);

        Button cancel = new Button(this);
        cancel.setText("닫기");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        cancelLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(cancel, cancelLp);
        root.addView(actions);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                status.setText(url == null ? "" : url);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        webView.loadUrl("https://www.youtube.com/");
    }

    private void saveCookies() {
        int count = CookieStore.importFromWebView();
        if (count <= 0 || !CookieStore.hasAuthCookies()) {
            Toast.makeText(this,
                    "로그인 쿠키를 찾지 못했습니다. Google 계정으로 로그인한 뒤 다시 저장하세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "로그인 쿠키를 저장했습니다. (" + count + "개)", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
