package com.pjinkim.sensors_data_logger;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;

import org.pytorch.helloworld.ImageNetClasses;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;
//import org.pytorch.torchvision.TensorImageUtils;
import org.pytorch.MemoryFormat;

import java.util.LinkedList;
import java.util.Queue;
import java.util.*;

public class MainActivity extends AppCompatActivity{

    // properties
    private final static String LOG_TAG = MainActivity.class.getName();

    private final static int REQUEST_CODE_ANDROID = 1001;
    private static String[] REQUIRED_PERMISSIONS = new String[] {
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    private IMUConfig mConfig = new IMUConfig();
    private IMUSession mIMUSession;

    private Handler mHandler = new Handler();
    private AtomicBoolean mIsRecording = new AtomicBoolean(false);
    private PowerManager.WakeLock mWakeLock;

    private TextView mLabelAccelDataX, mLabelAccelDataY, mLabelAccelDataZ;
    private TextView mLabelAccelBiasX, mLabelAccelBiasY, mLabelAccelBiasZ;
    private TextView mLabelGyroDataX, mLabelGyroDataY, mLabelGyroDataZ;
    private TextView mLabelGyroBiasX, mLabelGyroBiasY, mLabelGyroBiasZ;
    private TextView mLabelMagnetDataX, mLabelMagnetDataY, mLabelMagnetDataZ;
    private TextView mLabelMagnetBiasX, mLabelMagnetBiasY, mLabelMagnetBiasZ;
    private TextView mLabelWalkStatus;
    private TextView mLabelWalkInfo;
    private Button mStartStopButton;

    private Button mTestButton; //test

    private Queue<float[]> queue = new LinkedList<float[]>();

    private List<float[]> list = new ArrayList<float[]>();

    private TextView mLabelInterfaceTime;
    private Timer mInterfaceTimer = new Timer();
    private int mSecondCounter = 0;
    private long walkTime;
    private long stayTime;
    final int sequenceLength = 100;
    final int sequenceStep = sequenceLength / 2;
    int stepCounter = 0;
    private float[] meanValues = { /* Mean values for each feature */ };
    private float[] stdValues = { /* Standard deviation values for each feature */ };

    // for python
    private Python py;
    private PyObject testPython;



    // Android activity lifecycle states
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // enable python
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        py = Python.getInstance();
        testPython = py.getModule("test");

        walkTime = 0;
        stayTime = 0;
        try {
            meanValues = loadMeanValues();
            stdValues = loadStdValues();

            if (meanValues.length == 0 || stdValues.length == 0) {
                throw new IllegalStateException("Mean or Std values arrays are empty");
            }

            Log.d("MeanValues", "Loaded mean values: " + Arrays.toString(meanValues));
            Log.d("StdValues", "Loaded std values: " + Arrays.toString(stdValues));

        } catch (IOException e) {
            Log.e("SensorLogger", "Error loading mean and std values", e);
        } catch (IllegalStateException e) {
            Log.e("SensorLogger", "Mean or Std values arrays are empty", e);
        }

        // initialize screen labels and buttons
        initializeViews();

        // setup sessions
        mIMUSession = new IMUSession(this);

        // monitor various sensor measurements
        displayIMUSensorMeasurements();
        mLabelInterfaceTime.setText(R.string.ready_title);

        mLabelWalkInfo.setText("No info. Start recording");
        // print walk status
        recognizeWalkStatus();
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (!hasPermissions(this, REQUIRED_PERMISSIONS)) {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_ANDROID);
        }
        updateConfig();
    }


    @Override
    protected void onPause() {
        super.onPause();
    }


    @Override
    protected void onDestroy() {
        if (mIsRecording.get()) {
            stopRecording();
        }
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        mIMUSession.unregisterSensors();
        super.onDestroy();
    }

    //test

//    public void helloWorldButton(View view) {
//        Bitmap bitmap = null;
//        Module module = null;
//        try {
//            bitmap = BitmapFactory.decodeStream(getAssets().open("fashionista.jpg"));
//            module = LiteModuleLoader.load(assetFilePath(this, "model.pt"));
//        } catch (IOException e) {
//            Log.e("PytorchHelloWorld", "Error reading assets", e);
//            finish();
//        }
//
//        ImageView imageView = findViewById(R.id.image);
//        imageView.setImageBitmap(bitmap);
//
//        final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(bitmap,
//                TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB, MemoryFormat.CHANNELS_LAST);
//
//        final Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();
//        final float[] scores = outputTensor.getDataAsFloatArray();
//
//        float maxScore = -Float.MAX_VALUE;
//        int maxScoreIdx = -1;
//        for (int i = 0; i < scores.length; i++) {
//            if (scores[i] > maxScore) {
//                maxScore = scores[i];
//                maxScoreIdx = i;
//            }
//        }
//
//        String className = ImageNetClasses.IMAGENET_CLASSES[maxScoreIdx];
//
//        TextView textView = findViewById(R.id.text);
//        textView.setText(className);
//    }

    private float[] loadMeanValues() throws IOException {
        return loadAssetFile(this, "mean_values.txt");
    }

    private float[] loadStdValues() throws IOException {
        return loadAssetFile(this, "std_values.txt");
    }

    private float[] loadAssetFile(Context context, String assetName) throws IOException {
        String filePath = assetFilePath(context, assetName);

        File file = new File(filePath);
        if (!file.exists() || file.length() == 0) {
            return new float[0];
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String line;
            List<Float> valuesList = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                try {
                    float value = Float.parseFloat(line);
                    valuesList.add(value);
                } catch (NumberFormatException e) {
                    // Handle parse error
                }
            }
            if (valuesList.isEmpty()) {
                return new float[0];
            }
            float[] valuesArray = new float[valuesList.size()];
            for (int i = 0; i < valuesArray.length; i++) {
                valuesArray[i] = valuesList.get(i);
            }
            return valuesArray;
        }
    }

    public static String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = Files.newOutputStream(file.toPath())) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }



    // methods
    public void startStopRecording(View view) {
        if (!mIsRecording.get()) {

            // start recording sensor measurements when button is pressed
            startRecording();

            // start interface timer on display
            mSecondCounter = 0;
            mInterfaceTimer = new Timer();
            mInterfaceTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mSecondCounter += 1;
                            mLabelInterfaceTime.setText(interfaceIntTime(mSecondCounter));
                        }
                    });
                }
            }, 0, 1000);

        } else {

            // stop recording sensor measurements when button is pressed
            stopRecording();

            // stop interface timer on display
            if (mInterfaceTimer != null) {
                mInterfaceTimer.cancel();
                mInterfaceTimer = null;
            }
            mLabelInterfaceTime.setText(R.string.ready_title);
        }
    }



    private void startRecording() {

        // check button
        //mLabelWalkStatus.setText(testPython.callAttr("chooseText").toString());

        walkTime = 0;
        stayTime = 0;
        mLabelWalkInfo.setText("Recording...");

        // output directory for text files
        String outputFolder = null;
        try {
            OutputDirectoryManager folder = new OutputDirectoryManager(mConfig.getFolderPrefix(), mConfig.getSuffix());
            outputFolder = folder.getOutputDirectory();
            mConfig.setOutputFolder(outputFolder);
        } catch (IOException e) {
            showAlertAndStop("Cannot create output folder.");
            e.printStackTrace();
        }

        // start each session
        mIMUSession.startSession(outputFolder);
        mIsRecording.set(true);

        // update Start/Stop button UI
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mStartStopButton.setEnabled(true);
                mStartStopButton.setText(R.string.stop_title);
            }
        });
        showToast("Recording starts!");
    }


    protected void stopRecording() {
        //mLabelWalkStatus.setText(testPython.callAttr("chooseText").toString());
        long totalTime = walkTime + stayTime;
        String str;
        if (totalTime == 0) {
            str = "No data recorded.";
        } else {
            double walkPercentage = (double) walkTime / totalTime * 100;
            double stayPercentage = (double) stayTime / totalTime * 100;
            str = String.format("Walk: %.2f%%, Stay: %.2f%%", walkPercentage, stayPercentage);
        }

        mLabelWalkInfo.setText(str);


        mHandler.post(new Runnable() {
            @Override
            public void run() {

                // stop each session
                mIMUSession.stopSession();
                mIsRecording.set(false);

                // update screen UI and button
                showToast("Recording stops!");
                resetUI();
            }
        });
    }


    private static boolean hasPermissions(Context context, String... permissions) {

        // check Android hardware permissions
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }


    private void updateConfig() {
        final int MICRO_TO_SEC = 1000;
    }


    public void showAlertAndStop(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(text)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                stopRecording();
                            }
                        }).show();
            }
        });
    }


    public void showToast(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }


    private void resetUI() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mStartStopButton.setEnabled(true);
                mStartStopButton.setText(R.string.start_title);
            }
        });
    }


    @Override
    public void onBackPressed() {

        // nullify back button when recording starts
        if (!mIsRecording.get()) {
            super.onBackPressed();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode != REQUEST_CODE_ANDROID) {
            return;
        }

        for (int grantResult : grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                showToast("Permission not granted");
                finish();
                return;
            }
        }
    }


    private void initializeViews() {

        mLabelAccelDataX = (TextView) findViewById(R.id.label_accel_X);
        mLabelAccelDataY = (TextView) findViewById(R.id.label_accel_Y);
        mLabelAccelDataZ = (TextView) findViewById(R.id.label_accel_Z);

        mLabelAccelBiasX = (TextView) findViewById(R.id.label_accel_bias_X);
        mLabelAccelBiasY = (TextView) findViewById(R.id.label_accel_bias_Y);
        mLabelAccelBiasZ = (TextView) findViewById(R.id.label_accel_bias_Z);

        mLabelGyroDataX = (TextView) findViewById(R.id.label_gyro_X);
        mLabelGyroDataY = (TextView) findViewById(R.id.label_gyro_Y);
        mLabelGyroDataZ = (TextView) findViewById(R.id.label_gyro_Z);

        mLabelGyroBiasX = (TextView) findViewById(R.id.label_gyro_bias_X);
        mLabelGyroBiasY = (TextView) findViewById(R.id.label_gyro_bias_Y);
        mLabelGyroBiasZ = (TextView) findViewById(R.id.label_gyro_bias_Z);

        mLabelMagnetDataX = (TextView) findViewById(R.id.label_magnet_X);
        mLabelMagnetDataY = (TextView) findViewById(R.id.label_magnet_Y);
        mLabelMagnetDataZ = (TextView) findViewById(R.id.label_magnet_Z);

        mLabelMagnetBiasX = (TextView) findViewById(R.id.label_magnet_bias_X);
        mLabelMagnetBiasY = (TextView) findViewById(R.id.label_magnet_bias_Y);
        mLabelMagnetBiasZ = (TextView) findViewById(R.id.label_magnet_bias_Z);

        mLabelWalkStatus = (TextView) findViewById(R.id.walkStatus);

        mLabelWalkInfo = (TextView) findViewById(R.id.walkInfo);

        mStartStopButton = (Button) findViewById(R.id.button_start_stop);
        mLabelInterfaceTime = (TextView) findViewById(R.id.label_interface_time);

        //mTestButton = (Button) findViewById(R.id.test); //test
    }


    private void displayIMUSensorMeasurements() {

        // get IMU sensor measurements from IMUSession
        final float[] acce_data = mIMUSession.getAcceMeasure();
        final float[] acce_bias = mIMUSession.getAcceBias();

        final float[] gyro_data = mIMUSession.getGyroMeasure();
        final float[] gyro_bias = mIMUSession.getGyroBias();

        final float[] magnet_data = mIMUSession.getMagnetMeasure();
        final float[] magnet_bias = mIMUSession.getMagnetBias();

        // update current screen (activity)
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLabelAccelDataX.setText(String.format(Locale.US, "%.3f", acce_data[0]));
                mLabelAccelDataY.setText(String.format(Locale.US, "%.3f", acce_data[1]));
                mLabelAccelDataZ.setText(String.format(Locale.US, "%.3f", acce_data[2]));

                mLabelAccelBiasX.setText(String.format(Locale.US, "%.3f", acce_bias[0]));
                mLabelAccelBiasY.setText(String.format(Locale.US, "%.3f", acce_bias[1]));
                mLabelAccelBiasZ.setText(String.format(Locale.US, "%.3f", acce_bias[2]));

                mLabelGyroDataX.setText(String.format(Locale.US, "%.3f", gyro_data[0]));
                mLabelGyroDataY.setText(String.format(Locale.US, "%.3f", gyro_data[1]));
                mLabelGyroDataZ.setText(String.format(Locale.US, "%.3f", gyro_data[2]));

                mLabelGyroBiasX.setText(String.format(Locale.US, "%.3f", gyro_bias[0]));
                mLabelGyroBiasY.setText(String.format(Locale.US, "%.3f", gyro_bias[1]));
                mLabelGyroBiasZ.setText(String.format(Locale.US, "%.3f", gyro_bias[2]));

                mLabelMagnetDataX.setText(String.format(Locale.US, "%.3f", magnet_data[0]));
                mLabelMagnetDataY.setText(String.format(Locale.US, "%.3f", magnet_data[1]));
                mLabelMagnetDataZ.setText(String.format(Locale.US, "%.3f", magnet_data[2]));

                mLabelMagnetBiasX.setText(String.format(Locale.US, "%.3f", magnet_bias[0]));
                mLabelMagnetBiasY.setText(String.format(Locale.US, "%.3f", magnet_bias[1]));
                mLabelMagnetBiasZ.setText(String.format(Locale.US, "%.3f", magnet_bias[2]));
            }
        });

        // determine display update rate (100 ms)
        final long displayInterval = 100;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                displayIMUSensorMeasurements();
            }
        }, displayInterval);
    }

    private void recognizeWalkStatus() {

        // get IMU sensor measurements from IMUSession
        final float[] acce_data = mIMUSession.getAcceMeasure();
        final float[] gyro_data = mIMUSession.getGyroMeasure();
        //final float[] linacce_data = mIMUSession.getLinAcceMeasure();

        final int input_size = acce_data.length + gyro_data.length;// + linacce_data.length;

        float[] all_data = new float[input_size];
        // concatenation
        System.arraycopy(acce_data, 0, all_data, 0, acce_data.length);
        System.arraycopy(gyro_data, 0, all_data, acce_data.length, gyro_data.length);
        //System.arraycopy(linacce_data, 0, all_data, acce_data.length + gyro_data.length, linacce_data.length);

        // Standardize the data
        for (int i = 0; i < all_data.length; i++) {
            all_data[i] = (all_data[i] - meanValues[i]) / stdValues[i];
        }

        if (list.size() < sequenceLength) {
            list.add(all_data);
            mLabelWalkStatus.setText("wait");
        }
        else { // list.size() == sequenceLength + 1
            list.remove(0);
            list.add(all_data);

            if (stepCounter % sequenceStep == 0) {
                float[] flattenedArray = flattenListOfFloatArrays(list);
                Module module = null;
                try {
                    module = LiteModuleLoader.load(assetFilePath(this, "model_rnn_v2.pt"));
                } catch (IOException e) {
                    Log.e("SensorLogger", "Error reading assets", e);
                    finish();
                }

                final Tensor inputTensor = Tensor.fromBlob(flattenedArray, new long[]{1, sequenceLength, input_size});

                Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();

                final float[] scores = outputTensor.getDataAsFloatArray();

                if (scores[0] > 0.5) {
                    mLabelWalkStatus.setText("walk");
                    if (mIsRecording.get()) {
                        walkTime++;
                    }
                } else {
                    mLabelWalkStatus.setText("stay");
                    if (mIsRecording.get()) {
                        stayTime++;
                    }
                }
            }
            stepCounter++;

        }

        // determine display update rate (100 ms)
        final long displayInterval = 100;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                recognizeWalkStatus();
            }
        }, displayInterval);
    }

    public static float[] flattenListOfFloatArrays(List<float[]> listOfFloatArrays) {
        // Calculate the total length of the resulting array
        int totalLength = 0;
        for (float[] array : listOfFloatArrays) {
            totalLength += array.length;
        }

        // Create a new array with the calculated length
        float[] result = new float[totalLength];

        // Copy elements from each array in the list to the resulting array
        int currentIndex = 0;
        for (float[] array : listOfFloatArrays) {
            System.arraycopy(array, 0, result, currentIndex, array.length);
            currentIndex += array.length;
        }

        return result;
    }


    private String interfaceIntTime(final int second) {

        // check second input
        if (second < 0) {
            showAlertAndStop("Second cannot be negative.");
        }

        // extract hour, minute, second information from second
        int input = second;
        int hours = input / 3600;
        input = input % 3600;
        int mins = input / 60;
        int secs = input % 60;

        // return interface int time
        return String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs);
    }
}