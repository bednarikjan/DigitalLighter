package com.example.digitallighterserver;

import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Observable;
import java.util.Observer;
import java.util.Random;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import com.example.lightdetector.ColorManager;
import com.example.lightdetector.PointCollector;

public class DeviceMapper implements Observer, DeviceLocatingStrategy {

	PointCollector collector; // point collector
	DeviceMapperState state; // state of FSM
	int tilesX;
	int tilesY;
	boolean started = false;

	static long WAIT_TIME = 1000; // waiting time between sending a signal
								  // and taking a picture in miliseconds
	String RARE_COLOR = ColorManager.KEY_BLUE;
	double[] SHUT_DOWN_COLOR = ColorManager.getColor(ColorManager.KEY_BLACK);

	long startT;
	ArrayList<Point> falseAlarmDevices; // blobs that are shining with RARE
										// color when non of phone should light

	HashMap<String, ArrayList<Point>> lastDetectedBlobs = new HashMap<String, ArrayList<Point>>();
	HashMap<Point, ArrayList<Socket>> devices;
	int oneByOneCounter;
	final int RECOVERY_TRIES = 3;
	int recoveryCounter = 0;

	boolean detectionDone = false;

	ConnectionService network;

	// COLORS
	ArrayList<String> screenColors = new ArrayList<String>();

	public DeviceMapper(ConnectionService mConnection, int tilesX, int tilesY, Observer ca) {
		this.tilesX = tilesX;
		this.tilesY = tilesY;

		collector = new PointCollector(tilesX, tilesY);
		collector.addObserver(this);
		collector.addObserver(ca);
		network = mConnection;
		
		//reset();
	}
	
	public void reset() {
		state = DeviceMapperState.INIT;
		started = true;
		
		devices = new HashMap<Point, ArrayList<Socket>>();

		for (int i = 0; i < tilesX; i++)
			for (int j = 0; j < tilesY; j++) {
				ArrayList<Socket> list = new ArrayList<Socket>();
				devices.put(new Point(i, j), list);
			}
		oneByOneCounter = 0;
		lastDetectedBlobs.clear();

	}

	/**
	 * Process next frame, returns true when precedure is finished.
	 */
	public Boolean nextFrame(Mat image) {
		boolean finished = false;
		if (started) {
			detectionDone = false;
			finished = doFSMStep(image, false);
		} else {
			screenColors.clear();
			screenColors.add(RARE_COLOR);
			detectLights(image);
		}
		return finished;
	}

	@Override
	public void update(Observable obs, Object obj) {
		if (started) {
			lastDetectedBlobs = (HashMap<String, ArrayList<Point>>) obj;
			doFSMStep(null, true);
		}
	}

	public enum DeviceMapperState {
		INIT, // broadcast all devices to shine with initial color
		DETECT_FALSE_ALARM, // take a picture and find all possible devices
		DETECT_FALSE_ALARM_WAIT_FOR_UPDATE, // waiting until the image is
											// processed
		ONE_BY_ONE, // iterate through all of devices
					// make it light
		DETECT_ONE, // take a picure
		DETECT_ONE_WAIT_FOR_UPDATE, END;
	}

	/**
	 * Makes one step in FS machine, returns true if it is in final state.
	 * 
	 * @param image
	 * @param forceNextStep
	 */
	private Boolean doFSMStep(Mat image, Boolean forceNextStep) {
		Boolean retval = false;

		switch (state) {
		case INIT:
			recoveryCounter = 0;
			// broadcast all devices to shine with initial color
			network.broadcastCommandSignal(ColorManager.getHexColor(SHUT_DOWN_COLOR) + ":1000");
			startT = System.currentTimeMillis();
			state = DeviceMapperState.DETECT_FALSE_ALARM;
			break;
		case DETECT_FALSE_ALARM:
			// wait few seconds for devices to process the image
			if (System.currentTimeMillis() - startT > WAIT_TIME) {
				// take a picture and find all possible devices
				screenColors.clear();
				screenColors.add(RARE_COLOR);
				state = DeviceMapperState.DETECT_FALSE_ALARM_WAIT_FOR_UPDATE;
				detectLights(image);
			}
			break;
		case DETECT_FALSE_ALARM_WAIT_FOR_UPDATE:
			if (forceNextStep) { // collected send update, now we can process
									// blobs
				falseAlarmDevices = lastDetectedBlobs.get(RARE_COLOR);
				lastDetectedBlobs.clear();
				state = DeviceMapperState.ONE_BY_ONE;
			}
			break;
		case ONE_BY_ONE:
			if (oneByOneCounter >= network.getConnectedDevices().size()) {
				state = DeviceMapperState.END;
			} else {
				// iterate through all of devices
				// make it light
				network.unicastCommandSignal(network.getConnectedDevices().get(oneByOneCounter),
						ColorManager.getHexColor(ColorManager.getColor(RARE_COLOR)) + ":1000");
				startT = System.currentTimeMillis();
				state = DeviceMapperState.DETECT_ONE;
			}
			break;
		case DETECT_ONE:
			if (System.currentTimeMillis() - startT > WAIT_TIME) {
				screenColors.clear();
				screenColors.add(RARE_COLOR);
				state = DeviceMapperState.DETECT_ONE_WAIT_FOR_UPDATE;
				detectLights(image);
			}
			break;
		case DETECT_ONE_WAIT_FOR_UPDATE:
			if (forceNextStep) {
				for (Point p : falseAlarmDevices) {
					lastDetectedBlobs.get(RARE_COLOR).remove(p);
				}

				// randomly choose the tile
				if (lastDetectedBlobs.get(RARE_COLOR).size() > 0) { // phone
																	// detected
					Point tileOfDevice = lastDetectedBlobs.get(RARE_COLOR).get(
							getRandomInt(0, lastDetectedBlobs.size() - 1));
					// add to hash map
					devices.get(tileOfDevice).add(network.getConnectedDevices().get(oneByOneCounter));
					oneByOneCounter++;
					state = DeviceMapperState.INIT;
				} else if (recoveryCounter < RECOVERY_TRIES) { // not detected
																// but give
																// second chance
					recoveryCounter++;
					state = DeviceMapperState.ONE_BY_ONE;
				} else { // run out of second chances
					// TODO delete phone
					network.unicastCommandSignal(network.getConnectedDevices().get(oneByOneCounter), ColorManager.getHexColor(SHUT_DOWN_COLOR) + ":1000");
					oneByOneCounter++;
					state = DeviceMapperState.INIT;
				}
			}
			break;
		case END:
			/*for (int i = 0; i < 3; i++) {
				for (Socket s : devices.get(new Point(i, 0))) { // red
					network.unicastCommandSignal(s, "#ff0000:3000|#ff0000:1000|#000000:1000|#000000:1000|#ff0000:3000");
				}
			}

			for (int i = 0; i < 3; i++) {
				for (Socket s : devices.get(new Point(i, 1))) { // yellow
					network.unicastCommandSignal(s, "#ffcc00:3000|#000000:1000|#ffcc00:1000|#000000:1000|#ffcc00:3000");
				}
			}

			for (int i = 0; i < 3; i++) {
				for (Socket s : devices.get(new Point(i, 2))) { // green
					network.unicastCommandSignal(s, "#00ff00:3000|#000000:1000|#000000:1000|#00ff00:1000|#00ff00:3000");
				}
			}*/
			started = false;
			retval = true;
			break;
		default:
			// this should never happen
			break;
		}

		return retval;
	}

	private void detectLights(Mat image) {
		if (!detectionDone && collector != null) {
			collector.collect(image, screenColors);
			detectionDone = true;
		}
	}

	public static int getRandomInt(int min, int max) {
		Random r = new Random();
		return min + (int) r.nextInt(max - min + 1);
	}

	@Override
	public final HashMap<Point, ArrayList<Socket>> getDevices() {
		return devices;
	}
	
	public boolean isStarted() {
		return started;
	}

	public void setStarted(boolean started) {
		this.started = started;
	}	
}
