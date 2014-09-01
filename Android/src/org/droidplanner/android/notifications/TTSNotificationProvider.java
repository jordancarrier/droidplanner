package org.droidplanner.android.notifications;

import java.util.Locale;

import org.droidplanner.R;
import org.droidplanner.android.fragments.SettingsFragment;
import org.droidplanner.android.utils.prefs.DroidPlannerPrefs;
import org.droidplanner.core.drone.DroneInterfaces.DroneEventsType;
import org.droidplanner.core.drone.variables.Calibration;
import org.droidplanner.core.model.Drone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.widget.Toast;

import com.MAVLink.Messages.ApmModes;

/**
 * Implements DroidPlanner audible notifications.
 */
public class TTSNotificationProvider implements OnInitListener,
		NotificationHandler.NotificationProvider {

	private static final String TAG = TTSNotificationProvider.class.getSimpleName();

	private static final double BATTERY_DISCHARGE_NOTIFICATION_EVERY_PERCENT = 10;

    private final BroadcastReceiver mSpeechIntervalUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if(SettingsFragment.ACTION_UPDATED_STATUS_PERIOD.equals(action)) {
                statusInterval = mAppPrefs.getSpokenStatusInterval();
                handler.removeCallbacks(watchdogCallback);
                if (statusInterval != 0) {
                    handler.postDelayed(watchdogCallback, statusInterval * 1000);
                }
            }
        }
    };

	private TextToSpeech tts;
	private int lastBatteryDischargeNotification;

	private final Context context;
    private final DroidPlannerPrefs mAppPrefs;
	private final Handler handler = new Handler();
	private int statusInterval;

	private class Watchdog implements Runnable {
		private Drone drone;

		public void run() {
            handler.removeCallbacks(watchdogCallback);
			if(drone != null && drone.getMavClient().isConnected()) {
				speakPeriodic(drone);
			}

            if (statusInterval != 0) {
                handler.postDelayed(watchdogCallback, statusInterval * 1000);
            }
		}

        private void speakPeriodic(Drone drone) {
            final SparseBooleanArray speechPrefs = mAppPrefs.getPeriodicSpeechPrefs();

            final StringBuilder message = new StringBuilder();
            if (speechPrefs.get(R.string.pref_tts_periodic_bat_volt_key)) {
                message.append("battery " + drone.getBattery().getBattVolt() + " volts. ");
            }

            if (speechPrefs.get(R.string.pref_tts_periodic_alt_key)) {
                message.append("altitude, " + (int) (drone.getAltitude().getAltitude()) + " meters. ");
            }

            if (speechPrefs.get(R.string.pref_tts_periodic_airspeed_key)) {
                message.append("airspeed, "
                        + (int) (drone.getSpeed().getAirSpeed().valueInMetersPerSecond())
                        + " meters per second. ");
            }

            if (speechPrefs.get(R.string.pref_tts_periodic_rssi_key)) {
                message.append("r s s i, " + (int) drone.getRadio().getRssi() + " decibels");
            }

            speak(message.toString());
        }

		public void setDrone(Drone drone) {
			this.drone = drone;
		}
	}

	public final Watchdog watchdogCallback = new Watchdog();

	TTSNotificationProvider(Context context) {
		this.context = context;
		tts = new TextToSpeech(context, this);
        mAppPrefs = new DroidPlannerPrefs(context);
	}

	@Override
	public void onInit(int status) {
		if (status == TextToSpeech.SUCCESS) {
			// TODO: check if the language is available
			Locale ttsLanguage;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
				ttsLanguage = tts.getDefaultLanguage();
			} else {
				ttsLanguage = tts.getLanguage();
			}

			if (ttsLanguage == null) {
				ttsLanguage = Locale.US;
			}

			int supportStatus = tts.setLanguage(ttsLanguage);
			switch (supportStatus) {
			case TextToSpeech.LANG_MISSING_DATA:
			case TextToSpeech.LANG_NOT_SUPPORTED:
				tts.shutdown();
				tts = null;

				Log.e(TAG, "TTS Language data is not available.");
				Toast.makeText(context, "Unable to set 'Text to Speech' language!",
						Toast.LENGTH_LONG).show();
				break;
			}

            if(tts != null){
                //Register the broadcast receiver for the speech output period updates
                LocalBroadcastManager.getInstance(context).registerReceiver
                        (mSpeechIntervalUpdateReceiver, new IntentFilter(SettingsFragment.ACTION_UPDATED_STATUS_PERIOD));
            }
		} else {
			// Notify the user that the tts engine is not available.
			Log.e(TAG, "TextToSpeech initialization failed.");
			Toast.makeText(context,	"Please make sure 'Text to Speech' is enabled in the "
							+ "system accessibility settings.", Toast.LENGTH_LONG).show();
		}
	}

	private void speak(String string) {
		if (tts != null) {
			if (shouldEnableTTS()) {
				tts.speak(string, TextToSpeech.QUEUE_FLUSH, null);
			}
		}
	}

	private boolean shouldEnableTTS() {
		return mAppPrefs.prefs.getBoolean("pref_enable_tts", false);
	}

	/**
	 * Warn the user if needed via the TTSNotificationProvider module
	 */
	@Override
	public void onDroneEvent(DroneEventsType event, Drone drone) {
		if (tts != null) {
			switch (event) {
			case INVALID_POLYGON:
				Toast.makeText(context, R.string.exception_draw_polygon, Toast.LENGTH_SHORT).show();
				break;

			case ARMING:
				speakArmedState(drone.getState().isArmed());
				break;

			case ARMING_STARTED:
				speak("Arming the vehicle, please standby");
				break;

			case BATTERY:
				batteryDischargeNotification(drone.getBattery().getBattRemain());
				break;

			case MODE:
				speakMode(drone.getState().getMode());
				break;

			case MISSION_SENT:
				Toast.makeText(context, "Waypoints sent", Toast.LENGTH_SHORT).show();
				speak("Waypoints saved to Drone");
				break;

			case GPS_FIX:
				speakGpsMode(drone.getGps().getFixTypeNumeric());
				break;

			case MISSION_RECEIVED:
				Toast.makeText(context, "Waypoints received from Drone", Toast.LENGTH_SHORT).show();
				speak("Waypoints received");
				break;

			case HEARTBEAT_FIRST:
				watchdogCallback.setDrone(drone);
				speak("Connected");
				break;

			case HEARTBEAT_TIMEOUT:
				if (!Calibration.isCalibrating() && mAppPrefs.getWarningOnLostOrRestoredSignal()) {
					speak("Data link lost, check connection.");
					handler.removeCallbacks(watchdogCallback);
				}
				break;

			case HEARTBEAT_RESTORED:
                watchdogCallback.setDrone(drone);
				if(mAppPrefs.getWarningOnLostOrRestoredSignal()){
					speak("Data link restored");
				}
				break;

			case DISCONNECTED:
				handler.removeCallbacks(watchdogCallback);
				break;

			case MISSION_WP_UPDATE:
				speak("Going for waypoint " + drone.getMissionStats().getCurrentWP());
				break;

			case FOLLOW_START:
				speak("Following");
				break;

			case WARNING_400FT_EXCEEDED:
				if(mAppPrefs.getWarningOn400ftExceeded()){
					speak("warning, 400 feet exceeded");
				}
				break;

			case AUTOPILOT_WARNING:
				String warning = drone.getState().getWarning();
				if (drone.getState().isWarning() && mAppPrefs.getWarningOnAutopilotWarning()) {
					speak(warning);
				}
				break;

			case WARNING_SIGNAL_WEAK:
				if(mAppPrefs.getWarningOnLowSignalStrength()){
					speak("Warning, weak signal");
				}

			default:
				break;
			}
		}
	}

	private void speakArmedState(boolean armed) {
		if (armed) {
			speak("Armed");
		} else {
			speak("Disarmed");
		}
	}

	private void batteryDischargeNotification(double battRemain) {
		if (lastBatteryDischargeNotification > (int) ((battRemain - 1) / BATTERY_DISCHARGE_NOTIFICATION_EVERY_PERCENT)
				|| lastBatteryDischargeNotification + 1 < (int) ((battRemain - 1) / BATTERY_DISCHARGE_NOTIFICATION_EVERY_PERCENT)) {
			lastBatteryDischargeNotification = (int) ((battRemain - 1) / BATTERY_DISCHARGE_NOTIFICATION_EVERY_PERCENT);
			speak("Battery at" + (int) battRemain + "%");
		}
	}

	private void speakMode(ApmModes mode) {
		String modeString = "Mode ";
		switch (mode) {
		case FIXED_WING_FLY_BY_WIRE_A:
			modeString += "Fly by wire A";
			break;
		case FIXED_WING_FLY_BY_WIRE_B:
			modeString += "Fly by wire B";
			break;
		case ROTOR_ACRO:
			modeString += "Acrobatic";
			break;
		case ROTOR_ALT_HOLD:
			modeString += "Altitude hold";
			break;
		case ROTOR_POSITION:
			modeString += "Position hold";
			break;
		case FIXED_WING_RTL:
		case ROTOR_RTL:
			modeString += "Return to home";
			break;
		default:
			modeString += mode.getName();
			break;
		}
		speak(modeString);
	}

	private void speakGpsMode(int fix) {
		switch (fix) {
		case 2:
			speak("GPS 2D Lock");
			break;
		case 3:
			speak("GPS 3D Lock");
			break;
		default:
			speak("Lost GPS Lock");
			break;
		}
	}

	@Override
	public void quickNotify(String feedback) {
		speak(feedback);
	}

    @Override
    public void onTerminate() {
        if(tts != null){
            tts.shutdown();
        }
    }
}
