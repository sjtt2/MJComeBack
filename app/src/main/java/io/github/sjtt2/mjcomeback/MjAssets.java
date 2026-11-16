package io.github.sjtt2.mjcomeback;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 素材访问。
 *
 * <p>关键点：在宿主（抖音）进程里，{@code context.getAssets()} 拿到的是**抖音自己的** AssetManager，
 * 根本没有本模块的 {@code assets/mj/*}。因此这里直接按 zip 读取模块 APK：</p>
 *
 * <ul>
 *     <li>宿主进程：路径来自 {@code XposedInterface#getModuleApplicationInfo()#sourceDir}；</li>
 *     <li>模块 App 自身：退回 {@code context.getApplicationInfo()#sourceDir}。</li>
 * </ul>
 *
 * <p>只用 {@link ZipFile} 这种公开 API，不依赖 hidden API，也不依赖包可见性。</p>
 */
public final class MjAssets {

    private static final String TAG = "MJAssets";
    private static final String MODULE_PACKAGE = "io.github.sjtt2.mjcomeback";

    private static volatile ZipFile zip;
    private static volatile String moduleApkPath;
    private static volatile String modulePackage;

    private MjAssets() {
    }

    /** 在宿主进程的 onPackageReady 里调用一次。 */
    public static void init(ApplicationInfo moduleInfo) {
        try {
            if (moduleInfo != null) {
                moduleApkPath = moduleInfo.sourceDir;
                modulePackage = moduleInfo.packageName;
                Log.i(TAG, "module apk = " + moduleApkPath);
            }
        } catch (Throwable t) {
            Log.w(TAG, "init failed: " + t);
        }
    }

    private static ZipFile zip(Context host) throws IOException {
        ZipFile current = zip;
        if (current != null) return current;
        synchronized (MjAssets.class) {
            if (zip != null) return zip;
            String path = moduleApkPath;
            if (path == null && host != null) {
                // 只有模块 App 自己（设置页预览）才允许退回宿主自身 APK。
                // 宿主是抖音时必须拿到真正的模块 APK，否则读出来的是抖音的 assets，等于静默失效。
                boolean isModuleItself = MODULE_PACKAGE.equals(host.getPackageName())
                        || (modulePackage != null && modulePackage.equals(host.getPackageName()));
                if (isModuleItself) {
                    path = host.getApplicationInfo().sourceDir;
                }
            }
            if (path == null) {
                throw new IOException("module apk path unknown (moduleInfo not initialized)");
            }
            Log.i(TAG, "reading module assets from " + path);
            zip = new ZipFile(path);
            return zip;
        }
    }

    /** 读取 {@code assets/<name>} 的原始字节。 */
    public static byte[] read(Context host, String name) throws IOException {
        ZipFile file = zip(host);
        ZipEntry entry = file.getEntry("assets/" + name);
        if (entry == null) throw new IOException("asset not found: " + name);
        try (InputStream in = file.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            byte[] buffer = new byte[1 << 14];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 把素材落到宿主 cache 目录，返回可播放的 File（音效用）。 */
    public static File materialize(Context host, String name) throws IOException {
        byte[] data = read(host, name);
        File dir = new File(host.getCacheDir(), "mj_egg");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File out = new File(dir, name.substring(name.lastIndexOf('/') + 1));
        if (out.length() != data.length) {
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(data);
            }
        }
        return out;
    }
}
