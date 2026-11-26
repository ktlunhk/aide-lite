package com.aideui;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import java.io.File;

/**
 * Hosts the Kotlin compiler in its own ":kotlinc" process. A compiler out-of-memory or native crash
 * then kills only this process, never the IDE, and the heap is returned to Android after every build
 * because the process exits when a compile finishes. Protocol: MSG_COMPILE (+replyTo) -> MSG_RESULT.
 */
public class KotlinCompileService extends Service {
    static final int MSG_COMPILE = 1, MSG_RESULT = 2, MSG_CANCEL = 3;
    static final String K_COMPILER = "compiler", K_ARGS = "args", K_LOG = "log", K_STAGE = "stage",
        K_CACHE = "cache", K_PROPS = "props", K_CODE = "code", K_DETAIL = "detail";

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            if (msg.what == MSG_COMPILE) startCompile(msg);
            else if (msg.what == MSG_CANCEL) Process.killProcess(Process.myPid());
            else super.handleMessage(msg);
        }
    };
    private final Messenger messenger = new Messenger(handler);

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private void startCompile(Message msg) {
        final Bundle in = msg.getData();
        final Messenger reply = msg.replyTo;
        Thread worker = new Thread(null, new Runnable() {
            public void run() {
                String props = in.getString(K_PROPS);
                KotlinCompileRunner.Outcome o = KotlinCompileRunner.run(
                    new File(in.getString(K_COMPILER)), new File(in.getString(K_ARGS)),
                    new File(in.getString(K_LOG)), new File(in.getString(K_STAGE)),
                    new File(in.getString(K_CACHE)), props == null ? null : new File(props));
                Bundle out = new Bundle();
                out.putInt(K_CODE, o.code);
                out.putString(K_STAGE, o.stage);
                out.putString(K_DETAIL, o.detail);
                Message result = Message.obtain(null, MSG_RESULT);
                result.setData(out);
                try { if (reply != null) reply.send(result); } catch (RemoteException ignored) { }
                // Kotlin keeps large static state; a fresh process per build is the reliable cleanup.
                handler.postDelayed(new Runnable() {
                    public void run() { Process.killProcess(Process.myPid()); }
                }, 500);
            }
        }, "kotlinc", 512L * 1024L * 1024L);
        worker.start();
    }
}
