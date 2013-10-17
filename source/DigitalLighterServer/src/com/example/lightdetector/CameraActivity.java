package com.example.lightdetector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.example.digitallighterserver.ConnectionService;
import com.example.digitallighterserver.ConnectionService.LocalBinder;
import com.example.digitallighterserver.DeviceLocatingStrategy;
import com.example.digitallighterserver.DeviceMapper;
import com.example.digitallighterserver.DeviceTracker;
import com.example.digitallighterserver.MediaPlayer;
import com.example.digitallighterserver.R;
import com.example.digitallighterserver.ServiceObserver;

public class CameraActivity extends Activity implements CvCameraViewListener2, Observer, ServiceObserver {
	PointCollector collector;

	static int tilesX = 2;
	static int tilesY = 2;
	TextView info;
	BlockingQueue<HashMap<String, ArrayList<Point>>> buffer = new LinkedBlockingQueue<HashMap<String, ArrayList<Point>>>();

	int fpsCounter;
	ConnectionService mService;
	boolean mBound = false;

	DeviceLocatingStrategy dl;
	MediaPlayer mediaPlayer = null;

	// COLORS
	ArrayList<String> screenColors = new ArrayList<String>();

	private CameraBridgeViewBase mOpenCvCameraView;
	private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
		@Override
		public void onManagerConnected(int status) {
			switch (status) {
			case LoaderCallbackInterface.SUCCESS: {
				Log.i("Yo", "OpenCV loaded successfully");

				mOpenCvCameraView.enableView();
				// mOpenCvCameraView.setOnTouchListener(ColorBlobDetectionActivity.this);
			}
				break;
			default: {
				super.onManagerConnected(status);
			}
				break;
			}
		}
	};

	/** Defines callbacks for service binding, passed to bindService() */
	private ServiceConnection mConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			// We've bound to LocalService, cast the IBinder and get LocalService instance
			LocalBinder binder = (LocalBinder) service;
			mService = binder.getService();
			mBound = true;
			mService.setObserver(CameraActivity.this);
			dl = new DeviceMapper(mService, tilesX, tilesY, CameraActivity.this);
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			mBound = false;
		}
	};

	public void startDetection(View v) {
		startProcess = true;
	}

	private boolean startProcess = false;

	@Override
	public void onPause() {
		super.onPause();
		if (mOpenCvCameraView != null)
			mOpenCvCameraView.disableView();
	}

	@Override
	public void onResume() {
		super.onResume();
		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_3, this, mLoaderCallback);
		Intent serviceIntent = new Intent(this, ConnectionService.class);
		bindService(serviceIntent, mConnection, Context.BIND_ADJUST_WITH_ACTIVITY);
	}

	public void onDestroy() {
		super.onDestroy();
		if (mOpenCvCameraView != null)
			mOpenCvCameraView.disableView();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		setContentView(R.layout.color_blob_detection_surface_view);

		info = (TextView) findViewById(R.id.info_txt);
		mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.color_blob_detection_activity_surface_view);
		mOpenCvCameraView.setCvCameraViewListener(this);
	}

	@Override
	public void onCameraViewStarted(int width, int height) {
		// TODO Auto-generated method stub
		screenColors.add(ColorManager.KEY_BLUE);
		screenColors.add(ColorManager.KEY_GREEN);
		screenColors.add(ColorManager.KEY_RED);
	}

	@Override
	public void onCameraViewStopped() {
		// TODO Auto-generated method stub

	}

	@Override
	public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
		if (startProcess && dl != null) {
			if (dl.nextFrame(inputFrame.rgba())) { // mapping is finished
				// replace mapper with tracker
				dl = new DeviceTracker(tilesX, tilesY, dl.getDevices());
				// create media player
				mediaPlayer = new MediaPlayer(tilesX, tilesY, dl, mService);
			}
		}
		
		if (startProcess && mediaPlayer != null) {
			if (mediaPlayer.playNextFrame()) {
				// media is done, reset process?
			}
		}

		Mat image = drawTilesGrid(inputFrame.rgba(), tilesY, tilesY);
		if (buffer.size() > 0) {
			for (String colorItem : buffer.peek().keySet()) {
				for (Point tile : buffer.peek().get(colorItem)) {
					image = drawTile(image, (int) tile.x, (int) tile.y, ColorManager.getCvColor(colorItem));
				}
			}
			buffer.remove();
		}
		return image;
	}

	/*
	 * public void onPointCollectorUpdate(HashMap<String, ArrayList<Point>> update) { if (buffer.size() > 20)
	 * { buffer.clear(); } buffer.add(update); }
	 */

	public static Mat drawTilesGrid(Mat input, int tilesX, int tilesY) {
		Mat output = new Mat();
		input.copyTo(output);

		int unit = output.width() / tilesX;
		for (int i = 0; i < tilesX; ++i)
			Core.line(output, new Point(i * unit, 0), new Point(i * unit, output.height()),
					ColorManager.getCvColor(ColorManager.KEY_RED));

		unit = output.height() / tilesY;
		for (int i = 0; i < tilesY; ++i)
			Core.line(output, new Point(0, i * unit), new Point(output.width(), i * unit),
					ColorManager.getCvColor(ColorManager.KEY_RED));

		return output;
	}

	private static Mat drawTile(Mat input, int x, int y, Scalar color) {
		Mat output = new Mat(input.height(), input.width(), input.type(), new Scalar(0, 0, 0));
		input.copyTo(output);

		int unitX = output.width() / tilesX;
		int unitY = output.height() / tilesY;
		Core.rectangle(output, new Point(unitX * x, unitY * y), new Point(unitX * (x + 1), unitY * (y + 1)),
				color, 5);

		// Core.addWeighted(input, 1.0, output, 0.5, 0, output);
		return output;
	}

	@Override
	public void update(Observable observable, Object data) {
		HashMap<String, ArrayList<Point>> blobs = (HashMap<String, ArrayList<Point>>) data;
		if (buffer.size() > 20) {
			buffer.clear();
		}
		buffer.add(blobs);

	}

	public void updateTEST(HashMap<String, ArrayList<Point>> obj) {
		if (buffer.size() > 20) {
			buffer.clear();
		}
		buffer.add(obj);
	}

	@Override
	public void onServiceDataUpdate() {
		// When something change inside service like new device connected this function will be invoked
	}
}
