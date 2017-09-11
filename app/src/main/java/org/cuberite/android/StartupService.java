package org.cuberite.android;

/**
 * Copyright 2015 Western Digital Corporation. All rights reserved.
 * Based on https://github.com/cuberite/android
 *
 */

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.wdc.nassdk.BaseStartupService;
import com.wdc.nassdk.MyCloudUIServer;

public class StartupService extends BaseStartupService {
    private static final String TAG = StartupService.class.getName();
    public static final String MYCLOUD_ID = "MyCloudId";
    private String mycloud_id;
    public static String PACKAGE_NAME;

    /**
     * App must override this method and return the UI server instance.
     * @return
     */
    @Override
    public MyCloudUIServer createMyCloudUIServer() {
        Log.w(TAG, "Create myCloudUI");
        Context context = getApplicationContext();
        PACKAGE_NAME = context.getPackageName();
        Log.w(TAG, "Hello UI - " + PACKAGE_NAME + " at port " + MyCloudUIServer.getPort(this));
        return new CuberiteUIServer(context);
    }

    public StartupService() {
        Log.w(TAG, "StartupService.... ");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    /**
     * This onStartCommand() method will be called for each user in the My Cloud Device with different mycloud user-id.
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "StartupService onStartCommand.... ");
        if(intent.getExtras() != null) {
            Log.d(TAG, "StartupService extras -->" + intent.getExtras().getString(MYCLOUD_ID) + " Action-->" + intent.getAction());
            mycloud_id = intent.getExtras().getString(MYCLOUD_ID);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.e(TAG, "!!!! StartupService Destroyed !!!!!!");
    }
}
