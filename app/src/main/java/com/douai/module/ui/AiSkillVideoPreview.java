package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Plays an already validated private video file owned by the conversation. */
final class AiSkillVideoPreview extends Dialog {
    private final File localFile;
    private final VideoView video;
    private final MediaController controls;
    private final TextView status;
    private boolean active;

    AiSkillVideoPreview(Activity owner, File validatedLocalFile) {
        super(ModuleTheme.themedContext(owner), android.R.style.Theme_Material_NoActionBar);
        localFile = validatedLocalFile;
        setOwnerActivity(owner);
        setCanceledOnTouchOutside(false);

        Context context = getContext();
        LinearLayout content = new LinearLayout(context);
        content.setTag("ai.chat.video_preview");
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.BLACK);

        Button close = new Button(context);
        close.setTag("ai.chat.video_preview_close");
        close.setText("关闭");
        close.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams closeLayout = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        closeLayout.gravity = Gravity.END;
        content.addView(close, closeLayout);

        FrameLayout viewport = new FrameLayout(context);
        content.addView(viewport, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
        video = new VideoView(context);
        video.setTag("ai.chat.video_preview_player");
        video.setContentDescription("视频预览");
        video.setSecure(true);
        controls = new MediaController(context);
        video.setMediaController(controls);
        viewport.addView(video, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER));

        status = new TextView(context);
        status.setTag("ai.chat.video_preview_status");
        status.setTextColor(Color.WHITE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        viewport.addView(status, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER));
        video.setOnPreparedListener(player -> {
            if (active) status.setVisibility(View.GONE);
        });
        video.setOnErrorListener((player, what, extra) -> {
            if (active) showPlaybackFailure();
            return true;
        });

        setContentView(content);
        Window window = getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) window.setLayout(MATCH_PARENT, MATCH_PARENT);
        active = true;
        status.setText("正在读取视频…");
        status.setVisibility(View.VISIBLE);
        video.setVisibility(View.VISIBLE);
        try {
            if (localFile == null || !localFile.isAbsolute() || !localFile.isFile()) {
                showPlaybackFailure();
                return;
            }
            video.setVideoURI(Uri.fromFile(localFile));
            if (active) video.start();
        } catch (RuntimeException unavailable) {
            showPlaybackFailure();
        }
    }

    @Override protected void onStop() {
        try {
            releasePlayback();
        } finally {
            super.onStop();
        }
    }

    private void showPlaybackFailure() {
        status.setText("视频无法播放");
        status.setVisibility(View.VISIBLE);
        releasePlayback();
        video.setVisibility(View.INVISIBLE);
    }

    private void releasePlayback() {
        active = false;
        controls.hide();
        // Clear the source before releasing so a recreated surface cannot reopen the file.
        video.setVideoURI(null);
        video.suspend();
    }
}
