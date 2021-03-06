package org.cuberite.android;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Html;
import android.util.Log;

import java.io.File;
import java.io.OutputStream;
import java.util.Scanner;

public class CuberiteService extends IntentService {

    private String log;

    public CuberiteService() {
        super("CuberiteService");
    }

    private void addLog(String string) {
        String logLine = "";
        String[] text = string.split("\\n");
        for (String line : text) {
            String curText = Html.escapeHtml(line);
            if (curText.toLowerCase().startsWith("log: ")) {
                curText = curText.replaceFirst("(?i)log: ", "");
            } else if (curText.toLowerCase().startsWith("info:")) {
                curText = curText.replaceFirst("(?i)info: ", "");
                curText = "<font color= \"#FFA500\">" + curText + "</font>";
            } else if (curText.toLowerCase().startsWith("warning: ")) {
                curText = curText.replaceFirst("(?i)warning: ", "");
                curText = "<font color= \"#FF0000\">" + curText + "</font>";
            } else if (curText.toLowerCase().startsWith("error: ")) {
                curText = curText.replaceFirst("(?i)error: ", "");
                curText = "<font color=\"#8B0000\">" + curText + "</font>";
            }

            logLine += "<br>" + curText;
        }
        Log.w(Tags.SERVICE, logLine);
        //log += logLine;
        Intent intent = new Intent("addLog");
        intent.putExtra("message", logLine);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(Tags.SERVICE, "Starting service...");
        final String stopCommand = intent.getStringExtra("stopcommand");
        final String ip = intent.getStringExtra("ip");
        final String binary = intent.getStringExtra("binary");
        final String location = intent.getStringExtra("location");
        CharSequence text = getText(R.string.notification_cuberite_running);

        //PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
//        Notification notification = new NotificationCompat.Builder(this)
//                .setSmallIcon(R.mipmap.ic_shape)
//                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
//                .setTicker(text)
//                .setContentTitle(text)
//                .setContentText(ip)
//                .setContentIntent(contentIntent)
//                .build();
//        startForeground(1, notification);

        try {
            // Make sure we can execute the binary
            new File(binary).setExecutable(true, true);
            // Initiate ProcessBuilder with the command at the given location
            ProcessBuilder processBuilder = new ProcessBuilder(binary);
            processBuilder.directory(new File(location).getAbsoluteFile());
            processBuilder.redirectErrorStream(true);
            addLog("Info: Cuberite is starting...");
            Log.d(Tags.SERVICE, "Starting process...");
            final Process process = processBuilder.start();

            // Open STDIN for the inputLine
            final OutputStream cuberiteSTDIN = process.getOutputStream();

            // Logging thread. This thread will check cuberite's stdout (and stderr), color it and append it to the logView. This thread will wait only for next lines coming. if stdout is closed, this thread will exit
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.d(Tags.SERVICE, "Starting logging thread...");
                    Scanner processScanner = new Scanner(process.getInputStream());
                    while (processScanner.hasNextLine()) {
                        String line = processScanner.nextLine();
                        Log.i(Tags.PROCESS, line);
                        addLog(line);
                    }
                    processScanner.close();
                }
            }).start();

            // Communication with the activity
//            BroadcastReceiver getLog = new BroadcastReceiver() {
//                @Override
//                public void onReceive(Context context, Intent intent) {
//                    Intent sendIntent = new Intent("fullLog");
//                    sendIntent.putExtra("message", log);
//                    LocalBroadcastManager.getInstance(context).sendBroadcast(sendIntent);
//                }
//            };
            BroadcastReceiver stop = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        cuberiteSTDIN.write((stopCommand + "\n").getBytes());
                        cuberiteSTDIN.flush();
                    } catch (Exception e) {
                        Log.e(Tags.SERVICE, "An error occurred when writing " + stopCommand + " to the STDIN", e);
                    }
                }
            };
            BroadcastReceiver kill = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    process.destroy();
                }
            };
            BroadcastReceiver executeLine = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String line = intent.getStringExtra("message");
                    try {
                        cuberiteSTDIN.write((line + "\n").getBytes());
                        cuberiteSTDIN.flush();
                    } catch (Exception e) {
                        Log.e(Tags.SERVICE, "An error occurred when writing " + line + " to the STDIN", e);}
                }
            };
            //LocalBroadcastManager.getInstance(this).registerReceiver(getLog, new IntentFilter("getLog"));
            LocalBroadcastManager.getInstance(this).registerReceiver(stop, new IntentFilter("stop"));
            LocalBroadcastManager.getInstance(this).registerReceiver(kill, new IntentFilter("kill"));
            LocalBroadcastManager.getInstance(this).registerReceiver(executeLine, new IntentFilter("executeLine"));

            // Wait for the process to end. Logic waits here until cuberite has stopped. Everything after that is cleanup for the next run
            process.waitFor();

            //LocalBroadcastManager.getInstance(this).unregisterReceiver(getLog);
            LocalBroadcastManager.getInstance(this).unregisterReceiver(stop);
            LocalBroadcastManager.getInstance(this).unregisterReceiver(kill);
            LocalBroadcastManager.getInstance(this).unregisterReceiver(executeLine);
            cuberiteSTDIN.close();
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("callback"));
            stopSelf();
        } catch (Exception e) {
            Log.wtf(Tags.SERVICE, "An error occurred when starting Cuberite", e);
        }
    }
}
