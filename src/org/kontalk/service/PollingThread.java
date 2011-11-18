package org.kontalk.service;

import static org.kontalk.ui.MessagingNotification.NOTIFICATION_ID_POLLING_ERROR;

import java.util.List;

import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.client.AbstractMessage;
import org.kontalk.client.EndpointServer;
import org.kontalk.client.PollingClient;
import org.kontalk.ui.MessagingPreferences;

import android.accounts.Account;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The polling thread.
 * @author Daniele Ricci
 * @version 1.0
 */
public class PollingThread extends Thread {
    private final static String TAG = PollingThread.class.getSimpleName();

    private final static int MAX_ERRORS = 10;

    private final Context mContext;
    private final EndpointServer mServer;
    private String mAuthToken;
    private boolean mRunning;

    private PollingClient mClient;
    private MessageListener mListener;

    private String mPushRegistrationId;

    public PollingThread(Context context, EndpointServer server) {
        mServer = server;
        mContext = context;
    }

    public void setMessageListener(MessageListener listener) {
        this.mListener = listener;
    }

    public String getPushRegistrationId() {
        return mPushRegistrationId;
    }

    public void setPushRegistrationId(String regId) {
        mPushRegistrationId = regId;
    }

    public void run() {
        mRunning = true;
        mAuthToken = Authenticator.getDefaultAccountToken(mContext);
        if (mAuthToken == null) {
            Log.w(TAG, "invalid token - exiting");
            mRunning = false;
            return;
        }

        // exposing sensitive data - Log.d(TAG, "using token: " + mAuthToken);

        Account acc = Authenticator.getDefaultAccount(mContext);
        // exposing sensitive data - Log.d(TAG, "using account name " + acc.name + " as my number");
        Log.d(TAG, "using server " + mServer.toString());
        mClient = new PollingClient(mContext, mServer, mAuthToken, acc.name);

        int numErrors = 0;
        while(mRunning) {
            try {
                List<AbstractMessage<?>> list = mClient.poll();

                if (!mRunning) {
                    Log.d(TAG, "shutdown request");
                    break;
                }

                if (list != null) {
                    Log.i(TAG, list.toString());

                    if (mListener != null)
                        mListener.incoming(list);
                }

                // success - wait just 1s
                if (mRunning) {
                    if (list == null || list.size() == 0) {
                        // push notifications enabled - we can stop our parent :)
                        if (mPushRegistrationId != null) {
                            Log.d(TAG, "shutting down message center due to inactivity");
                            MessageCenterService.stopMessageCenter(mContext);
                        }
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {}
                }

                if (numErrors > 0)
                    numErrors--;
            }
            catch (Exception e) {
                Log.e(TAG, "polling error", e);
                numErrors++;
                if (mRunning) {
                    if (numErrors > MAX_ERRORS) {
                        notifyError();
                        numErrors = 0;
                    }

                    // error - wait longer - 5s
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e1) {}
                }
            }
        }
    }

    /**
     * Shuts down this polling thread gracefully.
     */
    public synchronized void shutdown() {
        Log.d(TAG, "shutting down");
        mRunning = false;
        if (mClient != null)
            mClient.abort();
        interrupt();
        // do not join - just discard the thread

        Log.d(TAG, "exiting");
        mClient = null;
    }

    private void notifyError() {
        // create intent for download error notification
        Intent i = new Intent(mContext, MessagingPreferences.class);
        PendingIntent pi = PendingIntent.getActivity(mContext.getApplicationContext(),
                NOTIFICATION_ID_POLLING_ERROR, i, Intent.FLAG_ACTIVITY_NEW_TASK);

        // create notification
        Notification no = new Notification(R.drawable.icon_stat,
                mContext.getString(R.string.notify_ticker_polling_error),
                System.currentTimeMillis());
        no.setLatestEventInfo(mContext.getApplicationContext(),
                mContext.getString(R.string.notify_title_polling_error),
                mContext.getString(R.string.notify_text_polling_error), pi);
        no.flags |= Notification.FLAG_AUTO_CANCEL;

        // notify!!
        NotificationManager nm = (NotificationManager) mContext
            .getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID_POLLING_ERROR, no);
    }
}
