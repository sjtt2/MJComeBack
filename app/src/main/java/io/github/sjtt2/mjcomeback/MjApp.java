package io.github.sjtt2.mjcomeback;

import android.app.Application;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** 注册 LSPosed 服务，拿到可写的 Remote Preferences，让设置页改的配置真正生效。 */
public final class MjApp extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "MJApp";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            XposedServiceHelper.registerListener(this);
        } catch (Throwable t) {
            Log.w(TAG, "register XposedService listener failed: " + t);
        }
    }

    @Override
    public void onServiceBind(XposedService service) {
        Log.i(TAG, "XposedService bound");
        MjService.set(service);
    }

    @Override
    public void onServiceDied(XposedService service) {
        Log.i(TAG, "XposedService died");
        MjService.set(null);
    }
}
