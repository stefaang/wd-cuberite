package org.cuberite.android;

import android.Manifest;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.wdc.nassdk.MyCloudUIServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import fi.iki.elonen.NanoHTTPD;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

/**
 * Copyright 2015 Western Digital Corporation. All rights reserved.
 * Based on https://github.com/cuberite/android - GPL-2.0 !
 */

/**
 * This is the UI class of the application and it overrrides the get() and post() methods to serve
 * different needs of this application.
 *
 * This class extends MyCloudUIServer and implement all required methods.
 */
public class CuberiteUIServer extends MyCloudUIServer {
    private static final String TAG = CuberiteUIServer.class.getName();
    private Context mContext;
    private InstallService installService;

    // cuberite attributes
    private SharedPreferences preferences;
    static String PACKAGE_NAME;
    static String PRIVATE_DIR;
    static String PUBLIC_DIR;
    public static final String KEY_MYCLOUD_USER = "mycloud_userid";
    private static final int COOKIE_EXPIRATION_PERIOD_DAYS = 30;
    private static final String ACCESS_TOKEN_NAME = "access_token";
    private static final int LIMIT = 1000;
    private static String serviceLogs = "";


    public CuberiteUIServer(Context context) {
        super(context);
        mContext = context;
        // initialize
        // PACKAGE_NAME: org.cuberite.android
        // PRIVATE_DIR: /data/data/org.cuberite.android/files
        // On most devices
        PACKAGE_NAME = StartupService.PACKAGE_NAME;
        PRIVATE_DIR = context.getFilesDir().getAbsolutePath();
        Log.d(TAG, "Starting Cuberite UI server with private dir:"+ PRIVATE_DIR);
        // Initialize variables
        preferences = context.getSharedPreferences(PACKAGE_NAME, Context.MODE_PRIVATE);

        initializeSettings(context, null);
    }

    /**
     * This get method serve as the landing screen for this application.
     *
     * @param ihttpSession
     * @return Response
     */
    @Override
    public Response get(IHTTPSession ihttpSession) {
        String token = getAccessToken(ihttpSession); //Get access token
        String baseUrl = getBaseUrl(ihttpSession); // get base url for further calls
        String uri = ihttpSession.getUri();

        Log.d(TAG, "##### base URL:" + baseUrl);
        if (uri != null) {
            if (uri.endsWith("status")) {
                // return the current status
                String response = checkState();
                return makeResponse(response);
            } else if (uri.endsWith("logs")) {
                // return the latest log lines
                String response = serviceLogs;
                // clear the logs
                serviceLogs = "";
                return makeResponse(response);
            }
        }
        // else update the Public Dir and return the main index.html page
        Log.d(TAG, "Set RootFolder: "+getMyCloudUserId(ihttpSession));
        PUBLIC_DIR = getRootFolder(mContext, getMyCloudUserId(ihttpSession));
        Response response = makeResponse(getViewSource());
        if (ihttpSession.getParms() != null && ihttpSession.getParms().containsKey(ACCESS_TOKEN_NAME)) {
            final NanoHTTPD.CookieHandler cookieHandler = new NanoHTTPD.CookieHandler(ihttpSession.getHeaders());

            cookieHandler.set(ACCESS_TOKEN_NAME, ihttpSession.getParms().get(ACCESS_TOKEN_NAME),
                    COOKIE_EXPIRATION_PERIOD_DAYS);

            cookieHandler.unloadQueue(response);
        }
        return response;
    }

    /**
     * The post method to serve users post requests. Anything UI want to send for backend service should ideally use post.
     * @param ihttpSession
     * @return
     */
    @Override
    public Response post(IHTTPSession ihttpSession) {
        String myclouduserid = getMyCloudUserId(ihttpSession);
        Log.d(TAG, "Got POST from user " + myclouduserid);
        PUBLIC_DIR = getRootFolder(mContext, myclouduserid);
        // initialize again to fetch new permissions and setup proper PUBLIC_DIR
        initializeSettings(mContext, PUBLIC_DIR);

        try{
            String postBody = ihttpSession.getQueryParameterString();

            Log.d(TAG, "##### Post Body:" + postBody);
            String action = null;

			// This is to get folder name from HTML Form data
            String[] keyValues = postBody.split("=");
            if (keyValues != null){
                String key = keyValues[0];
                if (key.equals("action")){
                    action = keyValues[1];
                    // workaround for chunked upload bug
                    action = (action.contains("\n"))? action.substring(0, action.indexOf('\r')) : action;
                    Log.d(TAG, "##### POST Action:" + action);
                }
            }
            switch (action) {
                case "Download":
                case "Install":
                    doInstall(getState());
                    break;
                case "Start":
                    doStart();
                    break;
                case "Stop":
                    doStop();
                    break;
                case "Update":
                    doInstall(State.NEED_DOWNLOAD_BOTH);
                    break;
                default:
                    Log.i(TAG, "Unsupported action: " + action);
                    return newFixedLengthResponse("<button onclick=\"window.history.back()\">Invalid Action</button>");
            }

            return newFixedLengthResponse("<button onclick=\"window.history.back()\">Back</button>");

        }catch (Exception e) {
            Log.d(TAG, "##### POST handling exception " + e.getMessage());
            return newFixedLengthResponse("<h1>Sorry, folder creation failed.</h1>");
        }
    }

    /**
     * Load html page from asset folder
     * @return
     */
    private String getViewSource() {
        try {
            StringBuilder builder = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(mContext.getAssets().open("index.html")));
            String line = "";
            while ((line = reader.readLine()) != null) {
                line = line.replaceAll("###IP_ADDRESS###", getIpAddress());
                builder.append(line + '\n');
            }
            reader.close();
            return builder.toString();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            return "<h1>Sorry index.html cannot be loaded</h1>";
        }
    }

    private Response makeResponse(String htmlContent) {
        return newFixedLengthResponse(new Response.IStatus() {
            @Override
            public int getRequestStatus() {
                return 200;
            }

            @Override
            public String getDescription() {
                return "OK";
            }
        }, MIME_HTML, htmlContent);
    }

    // Cuberite stuff
    public static void initializeSettings(Context context, String cubLocation) {
        SharedPreferences preferences = context.getSharedPreferences(StartupService.PACKAGE_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();

        int permission = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        editor.putBoolean("externalStoragePermission", permission == PERMISSION_GRANTED);
        Log.d(TAG, ((permission == PERMISSION_GRANTED)? "Got": "No") + " permission to write to external storage: ");
        Log.d(TAG, "Cuberite preferences mention external storage: " + preferences.contains("externalStoragePermission"));
        Log.d(TAG, "Cuberite preferences want to use external storage: " + preferences.getBoolean("externalStoragePermission", false));
        if (cubLocation == null) {
            if (permission == PERMISSION_GRANTED) {
                Log.d(TAG, "Cuberite to be installed in PUBLIC location");
                editor.putString("cuberiteLocation", PUBLIC_DIR + "/cuberite-server");
            } else {
                Log.d(TAG, "Cuberite to be installed in PRIVATE location");
                editor.putString("cuberiteLocation", PRIVATE_DIR + "/cuberite-server");
            }
        } else {
            Log.d(TAG, "Cuberite location to be installed in WD RootFolder: " + cubLocation + "/cuberite-server");
            editor.putString("cuberiteLocation", cubLocation + "/cuberite-server");
        }
        if (!preferences.contains("executableName"))
            editor.putString("executableName", "Cuberite");
        if (!preferences.contains("downloadHost"))
            editor.putString("downloadHost", "https://builds.cuberite.org/job/cuberite/job/master/job/android/job/release/lastSuccessfulBuild/artifact/android/Server/");
        editor.apply();
    }

    private State getState() {
        State state = null;
        boolean hasBinary = false;
        boolean hasServer = false;

        // Install state
        if (new File(PRIVATE_DIR + "/" + preferences.getString("executableName", "")).exists())
            hasBinary = true;
        if (new File(preferences.getString("cuberiteLocation", "")).exists())
            hasServer = true;

        if(isServiceRunning(CuberiteService.class))
            state = State.RUNNING;
        else if (hasBinary && hasServer)
            state = State.OK;
        else if (!hasServer && !hasBinary)
            state = State.NEED_DOWNLOAD_BOTH;
        else if (!hasServer)
            state = State.NEED_DOWNLOAD_SERVER;
        else
            state = State.NEED_DOWNLOAD_BINARY;

        Log.d(Tags.MAIN_ACTIVITY, "Getting State: " + state.toString());

        return state;
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     *  Check if Cuberite has EXTERNAL write permissions, otherwise writes to internal storage
     */
    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PERMISSION_GRANTED) {
            Log.d(Tags.MAIN_ACTIVITY, "Cuberite has no permissions for external storage!");
        } else if(preferences.getString("cuberiteLocation", "").startsWith(PRIVATE_DIR)){
            Log.d(Tags.MAIN_ACTIVITY, "Cuberite has permissions for external storage, but is still in the private dir");
        }
    }

    /**
     *  Check Cuberite state and prepare HTML for the web client
     */
    private String checkState() {
        checkPermissions();
        final State state = getState();
        if (state == State.RUNNING) {
//            int colorTo = ContextCompat.getColor(this, R.color.warning);
//            animateColorChange(mainButton, mainButtonColor, colorTo, 500);
//            mainButton.setText(getText(R.string.do_stop_cuberite));
//            mainButton.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View view) {
//                    doStop();
//                }
//            });
            return "RUNNING";
        } else if (state == State.OK) {
//            int colorTo = ContextCompat.getColor(this, R.color.success);
//            animateColorChange(mainButton, mainButtonColor, colorTo, 500);
//            mainButton.setText(getText(R.string.do_start_cuberite));
//            mainButton.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View view) {
//                    doStart();
//                }
//            });
            return "OK";
        } else {
//            int colorTo = ContextCompat.getColor(this, R.color.primary);
//            animateColorChange(mainButton, mainButtonColor, colorTo, 500);
//            mainButton.setText(getText(R.string.do_install_cuberite));
//            mainButton.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View view) {
//
//                }
//            });

            return "NOT_INSTALLED";
        }
    }

    /*
    *  Start the Cuberite server
    */
    protected void doStart() {
        Log.d(Tags.MAIN_ACTIVITY, "Starting cuberite");
        checkPermissions();

        Intent cubserviceintent = new Intent(mContext, CuberiteService.class);
        cubserviceintent.putExtra("location", preferences.getString("cuberiteLocation", ""));
        cubserviceintent.putExtra("binary", PRIVATE_DIR + "/" + preferences.getString("executableName", ""));
        cubserviceintent.putExtra("stopcommand", "stop");
        cubserviceintent.putExtra("ip", getIpAddress());
        BroadcastReceiver callback = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(Tags.MAIN_ACTIVITY, "Cuberite exited on process");
                LocalBroadcastManager.getInstance(context).unregisterReceiver(this);
                checkState(); // Sets the start button color correctly
            }
        };
        BroadcastReceiver addLog = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(Tags.MAIN_ACTIVITY, "Get logs from Cuberite Service");
                serviceLogs += intent.getStringExtra("message");
            }
        };
        LocalBroadcastManager.getInstance(mContext).registerReceiver(callback, new IntentFilter("callback"));
        LocalBroadcastManager.getInstance(mContext).registerReceiver(addLog, new IntentFilter("addLog"));
        mContext.startService(cubserviceintent);

//        int colorTo = ContextCompat.getColor(this, R.color.warning);
//        animateColorChange(mainButton, mainButtonColor, colorTo, 500);
//        mainButton.setText(getText(R.string.do_stop_cuberite));
//        mainButton.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                doStop();
//            }
//        });
    }

    /*
    *  Stop the Cuberite server
    */
    protected void doStop() {
        Log.d(Tags.MAIN_ACTIVITY, "Stopping Cuberite");
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(new Intent("stop"));
//        int colorTo = ContextCompat.getColor(this, R.color.danger);
//        animateColorChange(mainButton, mainButtonColor, colorTo, 500);
//        mainButton.setText(getText(R.string.do_kill_cuberite));
//        mainButton.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                doKill();
//            }
//        });
    }

    /*
    *  Kill the Cuberite server
    */
    protected void doKill() {
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(new Intent("kill"));
        checkState();
    }

    /*
    *  Get logs from the Cuberite server
    */
    protected void doGetLog() {

    }



    /*
    *  Install the Cuberite server
    */
    protected void doInstall(State state) {
        Intent intent = new Intent(mContext, InstallService.class);
        intent.setAction("install");
        intent.putExtra("downloadHost", preferences.getString("downloadHost", ""));
        intent.putExtra("state", state.toString());
        intent.putExtra("executableName", preferences.getString("executableName", ""));
        intent.putExtra("targetDirectory", preferences.getString("cuberiteLocation", ""));
        // download tracker is disabled
//        intent.putExtra("receiver", new DownloadReceiver(mContext, new Handler()));

        LocalBroadcastManager.getInstance(mContext).registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                LocalBroadcastManager.getInstance(context).unregisterReceiver(this);
                String error = intent.getStringExtra("error");
                if(error != null) {
                    Log.e(TAG, "Install error: "+error);
                }
                checkState();
            }
        }, new IntentFilter("InstallService.callback"));
        mContext.startService(intent);
    }

    /*
    *  Start the Cuberite server
    */



    /*
    *  Get the MyCloud UserID.
    *  In a testing environment, it returns "auth0|usernotfound"
    */
    public static String getMyCloudUserId(IHTTPSession session) {
        String userId = MyCloudUIServer.getMyCloudUserId(session);
        return (userId != null)? userId : "auth0|usernotfound";
    }

    /*
    *  Get the IPv4 address of the Cuberite server
    */
    public String getIpAddress() {
        try {
            for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements();) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.getName().contains("eth")) {
                    for (Enumeration<InetAddress> addresses = networkInterface.getInetAddresses(); addresses.hasMoreElements();) {
                        InetAddress address = addresses.nextElement();
                        if (!address.isLoopbackAddress() && (address.getAddress().length == 4)) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            return e.toString();
        }
        return null;
    }
}
