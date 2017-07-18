package com.novadart.reactnativenfc;


import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.AsyncTask;
import android.os.Parcelable;
import android.support.annotation.Nullable;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.novadart.reactnativenfc.parser.NdefParser;
import com.novadart.reactnativenfc.parser.TagParser;

public class ReactNativeNFCModule extends ReactContextBaseJavaModule implements ActivityEventListener,LifecycleEventListener {

    private static final String EVENT_NFC_DISCOVERED = "__NFC_DISCOVERED";

    private ReactApplicationContext reactContext;


    // caches the last message received, to pass it to the listeners when it reconnects
    private WritableMap startupNfcData;
    private boolean startupNfcDataRetrieved = false;

    private boolean startupIntentProcessed = false;

    private NfcAdapter mNfcAdapter;
    private MifareClassic tag;

    public ReactNativeNFCModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        reactContext.addActivityEventListener(this);
        reactContext.addLifecycleEventListener(this);
    }

    @Override
    public String getName() {
        return "ReactNativeNFC";
    }


    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {}

    @Override
    public void onNewIntent(Intent intent) {
        handleIntent(intent,false);
    }

    private void handleIntent(Intent intent, boolean startupIntent) {
        if (intent != null && intent.getAction() != null) {

            switch (intent.getAction()){

                case NfcAdapter.ACTION_NDEF_DISCOVERED:
                    Parcelable[] rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);

                    if (rawMessages != null) {

                        NdefMessage[] messages = new NdefMessage[rawMessages.length];
                        for (int i = 0; i < rawMessages.length; i++) {
                            messages[i] = (NdefMessage) rawMessages[i];
                        }
                        processNdefMessages(messages,startupIntent);
                    }
                    break;

                // ACTION_TAG_DISCOVERED is an unlikely case, according to https://developer.android.com/guide/topics/connectivity/nfc/nfc.html
                case NfcAdapter.ACTION_TAG_DISCOVERED:
                case NfcAdapter.ACTION_TECH_DISCOVERED:
                    Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
                    processTag(tag,startupIntent);
                    break;

            }
        }
    }

    public static void setupForegroundDispatch(final Activity activity, NfcAdapter adapter) {
        final Intent intent = new Intent(activity.getApplicationContext(), activity.getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        final PendingIntent pendingIntent = PendingIntent.getActivity(activity.getApplicationContext(), 0, intent, 0);
        adapter.enableForegroundDispatch(activity, pendingIntent, null, null);
    }

    public static void stopForegroundDispatch(final Activity activity, NfcAdapter adapter) {
        adapter.disableForegroundDispatch(activity);
    }

    /**
     * This method is used to retrieve the NFC data was acquired before the React Native App was loaded.
     * It should be called only once, when the first listener is attached.
     * Subsequent calls will return null;
     *
     * @param callback callback passed by javascript to retrieve the nfc data
     */
    @ReactMethod
    public void getStartUpNfcData(Callback callback){
        if(!startupNfcDataRetrieved){
            callback.invoke(DataUtils.cloneWritableMap(startupNfcData));
            startupNfcData = null;
            startupNfcDataRetrieved = true;
        } else {
            callback.invoke();
        }
        if (mNfcAdapter != null) {
            setupForegroundDispatch(getCurrentActivity(), mNfcAdapter);
        }
        else {
            mNfcAdapter = NfcAdapter.getDefaultAdapter(this.reactContext);
        }
    }


    private void sendEvent(@Nullable WritableMap payload) {
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(EVENT_NFC_DISCOVERED, payload); }


    private void processNdefMessages(NdefMessage[] messages, boolean startupIntent){
        NdefProcessingTask task = new NdefProcessingTask(startupIntent);
        task.execute(messages);
    }

    private void processTag(Tag tag, boolean startupIntent){
        TagProcessingTask task = new TagProcessingTask(startupIntent);
        task.execute(tag);
    }

    @ReactMethod
    public void hasNFC(Callback callback) {
        callback.invoke(checkNFC());
    }

    private boolean checkNFC() {
        return mNfcAdapter != null && mNfcAdapter.isEnabled();
    }

    @Override
    public void onHostResume() {
        if (mNfcAdapter != null) {
            setupForegroundDispatch(getCurrentActivity(), mNfcAdapter);
        }
        else {
            mNfcAdapter = NfcAdapter.getDefaultAdapter(this.reactContext);
        }
        if (!checkNFC()) {
            WritableMap noAdapter = new WritableNativeMap();
            noAdapter.putString("type", "ERROR");
            noAdapter.putString("message", "NO_ADAPTER_ERROR");
            sendEvent(noAdapter);
        }
        else {
            WritableMap adapter = new WritableNativeMap();
            adapter.putString("type", "INFO");
            adapter.putString("message", "ADAPTER_READY");
            sendEvent(adapter);
        }
    }


    @Override
    public void onHostPause() {
        if (mNfcAdapter != null)
            stopForegroundDispatch(getCurrentActivity(), mNfcAdapter);
    }


    @Override
    public void onHostDestroy() {}


    private class NdefProcessingTask extends AsyncTask<NdefMessage[],Void,WritableMap> {

        private final boolean startupIntent;

        NdefProcessingTask(boolean startupIntent) {
            this.startupIntent = startupIntent;
        }

        @Override
        protected WritableMap doInBackground(NdefMessage[]... params) {
            NdefMessage[] messages = params[0];
            return NdefParser.parse(messages);
        }

        @Override
        protected void onPostExecute(WritableMap ndefData) {
            if(startupIntent) {
                startupNfcData = ndefData;
            }
            sendEvent(ndefData);
        }
    }


    private class TagProcessingTask extends AsyncTask<Tag,Void,WritableMap> {

        private final boolean startupIntent;

        TagProcessingTask(boolean startupIntent) {
            this.startupIntent = startupIntent;
        }

        @Override
        protected WritableMap doInBackground(Tag... params) {
            Tag tag = params[0];
            return TagParser.parse(tag);
        }

        @Override
        protected void onPostExecute(WritableMap tagData) {
            if(startupIntent) {
                startupNfcData = tagData;
            }
            sendEvent(tagData);
        }
    }


}
