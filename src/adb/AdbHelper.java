package adb;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.android.tools.idea.sdk.AndroidSdks;
import com.intellij.ide.util.PropertiesComponent;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.List;

import Models.Command;
import Models.ExtraField;
import Models.IntentFlags;

/**
 * Created by vfarafonov on 26.08.2015.
 */
public class AdbHelper {
	private static final String ADB_PATH_RELATIVE_TO_SDK_ROOT = "/platform-tools/adb";
	private static final String COMMAND_SEND_BROADCAST_BASE = "am broadcast";
	private static final String COMMAND_START_ACTIVITY_BASE = "am start";
	private static final String COMMAND_START_SERVICE_BASE = "am startservice";
	private final static Object lock = new Object();
	private static final String ADB_PATH_KEY = "Adb_location";
	private static AdbHelper adbHelper_;
	private AndroidDebugBridge adb_;
	private String errorString_ = null;
	private TerminalOutputListener outputListener_;

	private AdbHelper() {
		AndroidDebugBridge.initIfNeeded(false);
	}

	public static AdbHelper getInstance() {
		AdbHelper instance = adbHelper_;
		if (instance == null) {
			synchronized (lock) {    // While we were waiting for the lock, another
				instance = adbHelper_;        // thread may have instantiated the object.
				if (instance == null) {
					instance = new AdbHelper();
					adbHelper_ = instance;
				}
			}
		}
		return instance;
	}

	/**
	 * Picks up adb location from plugin properties, AndroidSdkUtils or ANDROID_HOME environment variable
	 */
	public static String getAdbLocation() {
		PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();
		String adbPath = propertiesComponent.getValue(ADB_PATH_KEY);
		if (adbPath != null) {
			if (checkForAdbInPath(adbPath)) {
				return adbPath;
			}
		}
		Collection<File> sdkList = AndroidSdks.getInstance().getAndroidSdkPathsFromExistingPlatforms();
		if (sdkList.size() > 0) {
			String sdkPath = sdkList.iterator().next().getPath() + ADB_PATH_RELATIVE_TO_SDK_ROOT;
			if (checkForAdbInPath(sdkPath)) {
				saveAdbLocation(sdkPath);
				return sdkPath;
			}
		}
		String android_home = System.getenv("ANDROID_HOME");
		if (android_home != null) {
			String sdkPath = android_home + ADB_PATH_RELATIVE_TO_SDK_ROOT;
			if (checkForAdbInPath(sdkPath)) {
				saveAdbLocation(sdkPath);
				return sdkPath;
			}
		}
		return null;
	}

	/**
	 * Checks if specified adb directory exists and has adb file
	 */
	private static boolean checkForAdbInPath(String adbPath) {
		File adbDir = new File(adbPath).getParentFile();
		return adbDir.exists() &&
				adbDir.isDirectory() &&
				adbDir.list(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						return name.indexOf("adb") == 0;
					}
				}).length > 0;
	}

	/**
	 * Saves adb location to plugin properties
	 */
	public static void saveAdbLocation(String adbLocation) {
		PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();
		propertiesComponent.setValue(ADB_PATH_KEY, adbLocation);
	}

	public boolean initAdb(String adbLocation, AndroidDebugBridge.IDeviceChangeListener listener) {
		if (adbLocation != null && !adbLocation.isEmpty()) {
			try {
				adb_ = AndroidDebugBridge.createBridge(adbLocation, true);
				if (adb_ != null) {
					AndroidDebugBridge.addDeviceChangeListener(listener);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return adb_ != null;
	}

	public IDevice[] getDevices() {
		return adb_.getDevices();
	}

	/**
	 * Sends command to device.
	 *
	 * @return Error message if something went wrong, null if it is no errors
	 */
	private String sendCommand(CommandType type, IDevice device, String action, String data,
							   String category, String mime, String component, String user,
							   List<ExtraField> extras, List<IntentFlags> flags)
			throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
			IOException, IllegalArgumentException {
		if (device == null) {
			throw new IllegalArgumentException("Device cannot be null");
		}
		errorString_ = null;
		String fullCommand = getFullCommand(type, action, data, category, mime, component, user, extras, flags);
		device.executeShellCommand(fullCommand, new IShellOutputReceiver() {

			@Override
			public void addOutput(byte[] bytes, int i, int i1) {
				try {
					String output = new String(bytes, i, i1, "UTF-8");
					if (outputListener_ != null) {
						outputListener_.addOutput(output);
					}
					int index = output.indexOf("Error:");
					if (index == 0) {
						int lineEndingIndex = output.indexOf("\n");
						errorString_ = lineEndingIndex > -1 ? output.substring(0, lineEndingIndex) : output;
					} else if (index > 0) {
						Character beforeSymbol = output.charAt(index - 1);
						if (beforeSymbol == '\n') {
							errorString_ = output.substring(index);
							int lineEndingIndex = errorString_.indexOf("\n");
							if (lineEndingIndex > -1) {
								errorString_ = errorString_.substring(0, lineEndingIndex);
							}
						}
					}
				} catch (UnsupportedEncodingException e) {
					e.printStackTrace();
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public boolean isCancelled() {
				return false;
			}
		});
		return errorString_;
	}

	private String getFullCommand(CommandType type, String action, String data, String category, String mime, String component, String user, List<ExtraField> extras, List<IntentFlags> flags) {
		StringBuilder builder = new StringBuilder();
		if (user != null) {
			builder.append("run-as ");
			builder.append(user);
			builder.append(" ");
		}
		switch (type) {
			case BROADCAST:
				builder.append(COMMAND_SEND_BROADCAST_BASE);
				break;
			case START_ACTIVITY:
				builder.append(COMMAND_START_ACTIVITY_BASE);
				break;
			case START_SERVICE:
				builder.append(COMMAND_START_SERVICE_BASE);
				break;
		}
		if (action != null && action.length() > 0) {
			builder.append(" -a '").append(action).append("'");
		}
		if (data != null && data.length() > 0) {
			builder.append(" -d '").append(data).append("'");
		}
		if (category != null && category.length() > 0) {
			builder.append(" -c '").append(category).append("'");
		}
		if (mime != null && mime.length() > 0) {
			builder.append(" -t '").append(mime).append("'");
		}
		if (component != null && component.length() > 0) {
			builder.append(" -n '").append(component).append("'");
		}
		if (extras != null && extras.size() > 0) {
			for (ExtraField extra : extras) {
				builder.append(extra.getType().getPrefix()).append("'").append(extra.getKey()).append("' '").append(extra.getValue()).append("'");
			}
		}
		if (flags != null && flags.size() > 0) {
			for (IntentFlags flag : flags) {
				builder.append(flag.getCommand());
			}
		}
		if (user != null) {
			builder.append(" --user '0'");
		}
		System.out.println("Command: " + builder.toString());
		return builder.toString();
	}

	public boolean isConnected() {
		return adb_.isConnected();
	}

	public void restartAdb() {
		adb_.restart();
	}

	/**
	 * Sends command to device.
	 *
	 * @return Error message if something went wrong, null if it is no errors
	 */
	public String sendCommand(Command command, IDevice device) throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException {
		return sendCommand(command.getType(), device, command.getAction(), command.getData(), command.getCategory(), command.getMimeType(), command.getComponent(), command.getUser(), command.getExtras(), command.getFlags());
	}

	public void setOutputListener(TerminalOutputListener outputListener) {
		this.outputListener_ = outputListener;
	}

	public enum CommandType {
		BROADCAST, START_SERVICE, START_ACTIVITY
	}

	public interface TerminalOutputListener {
		void addOutput(String output);
	}
}
