package com.xsdanser.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启（v2.7 起常开，不再提供开关）。Android 12+ 可能禁止后台起前台服务，失败时记日志，用户打开 App 一次即可。 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = (i == null) ? null : i.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a);
        if (!boot) return;
        try {
            GuardService.start(c, GuardService.ACTION_START);
            GuardLog.i(c, "开机自启：已请求启动守护");
        } catch (Throwable t) {
            try { GuardLog.i(c, "开机自启被系统限制，请打开一次 App：" + t); }
            catch (Throwable ignored) {}
        }
    }
}
