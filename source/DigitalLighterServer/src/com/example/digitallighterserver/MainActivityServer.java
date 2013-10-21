package com.example.digitallighterserver;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.digitallighterserver.ConnectionService.LocalBinder;
import com.example.lightdetector.CameraActivity;

public class MainActivityServer extends Activity implements ServiceObserver {

	// LOGCAT TAG
	public static final String TAG = "DigitalLighterServer";

	ConnectionService mService;
	boolean mBound = false;

	// UI
	EditText serviceName;
	TextView txtUserCount;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.activity_main);

		// RETRIEVE UI ELEMENTS
		txtUserCount = (TextView) findViewById(R.id.user_counter);
		txtUserCount.setText(getString(R.string.user_number) + "0");
		serviceName = (EditText) findViewById(R.id.edit_service_name);

	}

	@Override
	protected void onStart() {
		super.onStart();

		// START SERVICE IF NOT STARTED AND BIND TO IT
		Intent serviceIntent = new Intent(this, ConnectionService.class);
		startService(serviceIntent);
		bindService(serviceIntent, mConnection, Context.BIND_IMPORTANT);
	}

	/** Defines callbacks for service binding, passed to bindService() */
	private ServiceConnection mConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			// We've bound to LocalService, cast the IBinder and get LocalService instance
			LocalBinder binder = (LocalBinder) service;
			mService = binder.getService();
			mBound = true;
			mService.setObserver(MainActivityServer.this);
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			mBound = false;
		}
	};

	// ========================================================================================================
	// REGISTER SERVICE TO ROUTER
	// ========================================================================================================

	public void clickAdvertise(View v) {
		String name = serviceName.getText().toString();
		if (serviceName == null || name.equals("")) {
			Toast.makeText(this, "Name the service", Toast.LENGTH_SHORT).show();
			return;
		}

		// Register service
		if (mBound)
			mService.registerService(name);
		else {
			Log.d(TAG, "Service is null.");
			Toast.makeText(this, "Service is null", Toast.LENGTH_SHORT).show();
		}

	}

	// ========================================================================================================
	// SEND COMMAND SIGNAL TO USER (5sec red color)
	// ========================================================================================================

	public void clickRed(View v) {
		mService.broadcastCommandSignal("#ff0000:5000");
	}

	// ========================================================================================================
	// SEND COMMAND SIGNAL TO USER (7sec green color)
	// ========================================================================================================

	public void clickGreen(View v) {
		mService.broadcastCommandSignal("#00ff00:7000");
	}

	// ========================================================================================================
	// SEND COMMAND SIGNAL TO USER (3sec blue color)
	// ========================================================================================================

	public void clickBlue(View v) {
		mService.broadcastCommandSignal("#0000ff:3000");
	}

	// ========================================================================================================
	// SEND COMMAND SIGNAL TO USER (3sec blue color)
	// ========================================================================================================

	public void clickAllThree(View v) {
		mService.broadcastCommandSignal("#ff0000:5000|#00ff00:7000|#0000ff:3000");
	}

	// ========================================================================================================
	// SENDING COMMAND SIGNAL
	// ========================================================================================================

	public void clickCamera(View v) {
		//startActivity(new Intent(MainActivityServer.this, CameraActivity.class));
		mService.pingUsers();
	}

	@Override
	public void onServiceDataUpdate() {
		txtUserCount.setText(getString(R.string.user_number) + mService.userCount);
		serviceName.setText(mService.serviceName);
	}

}
