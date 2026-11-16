package io.github.sjtt2.mjcomeback;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 彩蛋播放器：在当前窗口顶层叠一层透明、不可点击的 {@link FrameLayout}，
 * 用 {@link AnimatedImageDrawable} 播放带 Alpha 通道的动画 WebP，并播放原版音效。
 */
public final class MjOverlay {

    private static final String TAG = "MJOverlay";

    /** 素材实际时长约 3.0s，多留一点余量保证播完。 */
    private static final long PLAYBACK_MS = 3150L;

    private static final int MAX_TARGET_WIDTH = 720;
    private static final int MIN_TARGET_WIDTH = 480;

    private static final String[] ASSET_NAMES = {"mj_drop", "mj_swing"};

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mj-decode");
        thread.setDaemon(true);
        return thread;
    });

    private MjOverlay() {
    }

    /** 0 = 吊下来，1 = 荡秋千。 */
    public static int nextIndex(int mode) {
        if (mode == MjSettings.MODE_RANDOM) {
            return (int) (Math.random() * 2);
        }
        return Math.abs(COUNTER.getAndIncrement() % 2);
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    public static void show(ViewGroup root, Context context, int index, boolean withSound) {
        Log.d(TAG, "show index=" + index + " root=" + root.getClass().getName()
                + " attached=" + (root != null && root.isAttachedToWindow()));
        if (root == null || context == null) return;
        if (!isSupported()) {
            Log.w(TAG, "AnimatedImageDrawable requires API 28+");
            return;
        }
        if (!root.isAttachedToWindow()) {
            Log.w(TAG, "root view not attached, skip");
            return;
        }

        final String asset = ASSET_NAMES[Math.max(0, Math.min(1, index))];

        Session previous = findExisting(root);
        if (previous != null) previous.stop();

        FrameLayout host = new FrameLayout(context);
        host.setLayoutParams(visibleAreaParams(root));
        host.setClickable(false);
        host.setLongClickable(false);
        host.setFocusable(false);
        host.setClipChildren(false);
        host.setElevation(1000f);

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setClickable(false);
        image.setFocusable(false);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        imageParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        host.addView(image, imageParams);

        Session session = new Session(host, image);
        host.setTag(session);
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                session.stop();
            }
        });

        root.addView(host);
        Log.d(TAG, "overlay added, params=" + host.getLayoutParams().width + "x"
                + host.getLayoutParams().height);

        if (withSound) {
            session.playSound(context, asset);
        }

        final int targetWidth = targetWidth(context);
        DECODER.execute(() -> {
            if (session.stopped) return;
            Drawable drawable = null;
            try {
                drawable = decode(context, asset, targetWidth);
            } catch (Throwable t) {
                Log.w(TAG, "decode " + asset + " failed: " + t);
            }
            final Drawable decoded = drawable;
            host.post(() -> {
                if (session.stopped) return;
                if (decoded == null) {
                    session.stop();
                    return;
                }
                session.attachDrawable(decoded);
                Log.d(TAG, "drawable attached: " + decoded.getClass().getName());
            });
        });

        host.postDelayed(session, PLAYBACK_MS);
    }

    private static int targetWidth(Context context) {
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        return Math.max(MIN_TARGET_WIDTH, Math.min(MAX_TARGET_WIDTH, screenWidth));
    }

    /**
     * 只铺「键盘上方」的可见区域。输入法窗口永远盖在应用窗口之上，
     * 铺满全屏的话动画会被键盘拦腰截断，这里按可见区域重新定尺寸并居中。
     */
    private static FrameLayout.LayoutParams visibleAreaParams(ViewGroup root) {
        int rootHeight = root.getHeight();
        android.graphics.Rect frame = new android.graphics.Rect();
        try {
            root.getWindowVisibleDisplayFrame(frame);
        } catch (Throwable ignored) {
            frame.setEmpty();
        }
        int top = frame.top;
        int bottom = frame.bottom;
        if (frame.isEmpty() || bottom <= top || rootHeight <= 0) {
            return new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        bottom = Math.min(bottom, rootHeight);
        int height = Math.max(1, bottom - top);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = Math.max(0, top);
        return params;
    }

    private static Session findExisting(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            Object tag = root.getChildAt(i).getTag();
            if (tag instanceof Session) return (Session) tag;
        }
        return null;
    }

    private static Drawable decode(Context context, String asset, int targetWidth) throws Exception {
        byte[] data = MjAssets.read(context, "mj/" + asset + ".webp");
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
        buffer.put(data);
        buffer.flip();
        ImageDecoder.Source source = ImageDecoder.createSource(buffer);
        return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
            Size size = info.getSize();
            if (targetWidth > 0 && size.getWidth() > targetWidth) {
                int height = Math.max(1,
                        Math.round(size.getHeight() * (targetWidth / (float) size.getWidth())));
                decoder.setTargetSize(targetWidth, height);
            }
        });
    }

    /** 一次播放的生命周期，挂在 host 的 tag 上，避免静态 Map 造成引用环。 */
    private static final class Session implements Runnable {
        private final FrameLayout host;
        private final ImageView image;
        private Drawable drawable;
        private MediaPlayer player;
        private volatile boolean stopped;

        Session(FrameLayout host, ImageView image) {
            this.host = host;
            this.image = image;
        }

        @Override
        public void run() {
            stop();
        }

        void attachDrawable(Drawable d) {
            drawable = d;
            image.setImageDrawable(d);
            if (d instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable animated = (AnimatedImageDrawable) d;
                animated.setRepeatCount(0);
                animated.start();
            }
        }

        void playSound(Context context, String asset) {
            MediaPlayer created = new MediaPlayer();
            try {
                File file = MjAssets.materialize(context, "mj/" + asset + ".m4a");
                created.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                created.setDataSource(file.getAbsolutePath());
                created.setOnPreparedListener(MediaPlayer::start);
                created.setOnCompletionListener(mp -> {
                    if (player == mp) player = null;
                    try {
                        mp.release();
                    } catch (Throwable ignored) {
                    }
                });
                created.prepareAsync();
                player = created;
            } catch (Throwable t) {
                Log.w(TAG, "play sound failed: " + t);
                try {
                    created.release();
                } catch (Throwable ignored) {
                }
                player = null;
            }
        }

        void stop() {
            if (stopped) return;
            stopped = true;

            host.removeCallbacks(this);
            if (drawable instanceof Animatable) {
                try {
                    ((Animatable) drawable).stop();
                } catch (Throwable ignored) {
                }
            }
            image.setImageDrawable(null);
            host.setTag(null);

            if (host.getParent() instanceof ViewGroup) {
                ((ViewGroup) host.getParent()).removeView(host);
            }

            MediaPlayer p = player;
            player = null;
            if (p != null) {
                try {
                    if (p.isPlaying()) p.stop();
                } catch (Throwable ignored) {
                }
                try {
                    p.reset();
                    p.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
