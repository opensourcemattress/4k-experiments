/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2video;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2VideoFragment extends Fragment
        implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback {

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    private static final String TAG = "Camera2VideoFragment";
    private static final int REQUEST_VIDEO_PERMISSIONS = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
    };

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * Button to record video
     */
    private Button mButtonVideo;

    /**
     * A reference to the opened {@link android.hardware.camera2.CameraDevice}.
     */
    private static int N_CAMERAS = 3;
    private final int COLOR_ID = 0;
    private final int MONO_ID = 2;

    private ImageReader mImageReader;

    private CameraDevice[] mCameraDevices = new CameraDevice[N_CAMERAS];

//    private CameraDevice mCameraDevice;
//    private CameraDevice mCameraDeviceMono;

    /**
     * A reference to the current {@link android.hardware.camera2.CameraCaptureSession} for
     * preview.
     */
    private CameraCaptureSession[] mPreviewSessions = new CameraCaptureSession[N_CAMERAS];


//    private CameraCaptureSession mPreviewSession;
//    private CameraCaptureSession mPreviewSessionMono;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                                              int width, int height) {
            openCameras(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                                                int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }

    };

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
     * The {@link android.util.Size} of video recording.
     */
    private Size mVideoSize;

    /**
     * MediaRecorder
     */
    private MediaRecorder[] mMediaRecorders = new MediaRecorder[N_CAMERAS];
//    private MediaRecorder mMediaRecorderMono;

    /**
     * Whether the app is recording video now
     */
    private boolean mIsRecordingVideo;

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread[] mBackgroundThreads = new HandlerThread[N_CAMERAS];

//    private HandlerThread mBackgroundThread;


    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler[] mBackgroundHandlers = new Handler[N_CAMERAS];

//    private Handler mBackgroundHandler;


    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
//    private Semaphore[] mCameraOpenCloseLocks = new Semaphore[N_CAMERAS];

    private Semaphore mCameraOpenCloseLockColor = new Semaphore(1);
    private Semaphore mCameraOpenCloseLockMono = new Semaphore(1);

//    CaptureRequest.Key<Byte> BayerMonoLinkEnableKey =
//            new CaptureRequest.Key<>("org.codeaurora.qcamera3.dualcam_link_meta_data.enable",
//                    Byte.class);
//    CaptureRequest.Key<Byte> BayerMonoLinkMainKey =
//            new CaptureRequest.Key<>("org.codeaurora.qcamera3.dualcam_link_meta_data.is_main",
//                    Byte.class);
//    CaptureRequest.Key<Integer> BayerMonoLinkSessionIdKey =
//            new CaptureRequest.Key<>("org.codeaurora.qcamera3.dualcam_link_meta_data" +
//                    ".related_camera_id", Integer.class);
//
//    public void linkBayerMono(int id) {
//        Log.d(TAG, "linkBayerMono " + id);
//        if (id == COLOR_ID) {
//            mPreviewBuilders[id].set(BayerMonoLinkEnableKey, (byte) 1);
//            mPreviewBuilders[id].set(BayerMonoLinkMainKey, (byte) 1);
//            mPreviewBuilders[id].set(BayerMonoLinkSessionIdKey, MONO_ID);
//        } else if (id == MONO_ID) {
//            mPreviewBuilders[id].set(BayerMonoLinkEnableKey, (byte) 1);
//            mPreviewBuilders[id].set(BayerMonoLinkMainKey, (byte) 0);
//            mPreviewBuilders[id].set(BayerMonoLinkSessionIdKey, COLOR_ID);
//        }
//    }
//
//    public void unLinkBayerMono(int id) {
//        Log.d(TAG, "unlinkBayerMono " + id);
//        if (id == COLOR_ID) {
//            mPreviewBuilders[id].set(BayerMonoLinkEnableKey, (byte) 0);
//        } else if (id == MONO_ID) {
//            mPreviewBuilders[id].set(BayerMonoLinkEnableKey, (byte) 0);
//        }
//    }

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its status.
     */
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            int id = Integer.valueOf(cameraDevice.getId());
//            mCameraDevice = cameraDevice;
            mCameraDevices[id] = cameraDevice;
            if (id == COLOR_ID) {
                startPreview(id);
                mCameraOpenCloseLockColor.release();
                if (null != mTextureView) {
                    configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
                }
            }
            else
            {
                startPreview(id);
                mCameraOpenCloseLockMono.release();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            int id = Integer.valueOf(cameraDevice.getId());
            if (id == COLOR_ID)
                mCameraOpenCloseLockColor.release();
            else
                mCameraOpenCloseLockMono.release();

            cameraDevice.close();
            mCameraDevices[id] = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            int id = Integer.valueOf(cameraDevice.getId());

            if (id == COLOR_ID)
                mCameraOpenCloseLockColor.release();
            else
                mCameraOpenCloseLockMono.release();

            cameraDevice.close();
            mCameraDevices[id] = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    private Integer mSensorOrientation;
    private String[] mNextVideoAbsolutePaths = new String[N_CAMERAS];

//    private CaptureRequest.Builder mPreviewBuilder;
    private CaptureRequest.Builder[] mPreviewBuilders = new CaptureRequest.Builder[N_CAMERAS];

    public static Camera2VideoFragment newInstance() {
        return new Camera2VideoFragment();
    }

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_video, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mButtonVideo = (Button) view.findViewById(R.id.video);
        mButtonVideo.setOnClickListener(this);
        view.findViewById(R.id.info).setOnClickListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread(COLOR_ID);
        startBackgroundThread(MONO_ID);

        mImageReader = ImageReader.newInstance(3840, 2160, ImageFormat.YUV_420_888, 10);
        mImageReader.setOnImageAvailableListener(
                new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        Image image = reader.acquireNextImage();
                        image.close();
                    }
                }, mBackgroundHandlers[MONO_ID]);

        if (mTextureView.isAvailable()) {
            openCameras(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera(COLOR_ID);
//        closeCamera(MONO_ID);

        stopBackgroundThread(COLOR_ID);
//        stopBackgroundThread(MONO_ID);

        super.onPause();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.video: {
                if (mIsRecordingVideo) {
                    stopRecordingVideo(COLOR_ID);
                    stopRecordingVideo(MONO_ID);

                } else {
                    startRecordingVideo(COLOR_ID);
                    startRecordingVideo(MONO_ID);

                }
                break;
            }
            case R.id.info: {
                Activity activity = getActivity();
                if (null != activity) {
                    new AlertDialog.Builder(activity)
                            .setMessage(R.string.intro_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                break;
            }
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread(int id) {
        mBackgroundThreads[id] = new HandlerThread("CameraBackground");
        mBackgroundThreads[id].start();
        mBackgroundHandlers[id] = new Handler(mBackgroundThreads[id].getLooper());
    }
//    private void startBackgroundThread() {
//        mBackgroundThread = new HandlerThread("CameraBackground");
//        mBackgroundThread.start();
//        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
//    }
//


    /**
     * Stops the background thread and its {@link Handler}.
     */
//    private void stopBackgroundThread() {
//        mBackgroundThread.quitSafely();
//        try {
//            mBackgroundThread.join();
//            mBackgroundThread = null;
//            mBackgroundHandler = null;
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//
//    }

    private void stopBackgroundThread(int id) {
        mBackgroundThreads[id].quitSafely();
        try {
            mBackgroundThreads[id].join();
            mBackgroundThreads[id] = null;
            mBackgroundHandlers[id] = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private boolean shouldShowRequestPermissionRationale(String[] permissions) {
        for (String permission : permissions) {
            if (FragmentCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Requests permissions needed for recording video.
     */
    private void requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            FragmentCompat.requestPermissions(this, VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.length == VIDEO_PERMISSIONS.length) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        break;
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private boolean hasPermissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tries to open a {@link CameraDevice}. The result is listened by `mStateCallback`.
     */
    @SuppressWarnings("MissingPermission")
    private void openCameras(int width, int height) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions();
            return;
        }
        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing()) {
            return;
        }
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "tryAcquire");
            if (!mCameraOpenCloseLockColor.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock color camera opening.");
            }
//            String cameraId = manager.getCameraIdList()[0];
             String[] cameraIds = manager.getCameraIdList();

            String cameraId = String.valueOf(COLOR_ID);
            String cameraIdMono = String.valueOf(MONO_ID);

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null) {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }
            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
//            mVideoSize = map.getOutputSizes(MediaRecorder.class)[0];
//            mVideoSize = map.getHighResolutionOutputSizes(MediaRecorder.OutputFormat.MPEG_4)[0];
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, mVideoSize);

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            configureTransform(width, height);
            mMediaRecorders[COLOR_ID] = new MediaRecorder();
            mMediaRecorders[MONO_ID] = new MediaRecorder();

            manager.openCamera(cameraIdMono, mStateCallback, null);

            manager.openCamera(cameraId, mStateCallback, null);

        } catch (CameraAccessException e) {
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
            activity.finish();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    private void closeCamera(int id) {
        try {
            if (id == COLOR_ID)
                mCameraOpenCloseLockColor.acquire();
            else
                mCameraOpenCloseLockMono.acquire();

            closePreviewSession(id);
            if (null != mCameraDevices[id]) {
                mCameraDevices[id].close();
                mCameraDevices[id] = null;
            }
            if (null != mMediaRecorders[id]) {
                mMediaRecorders[id].release();
                mMediaRecorders[id] = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            if (id == COLOR_ID)
                mCameraOpenCloseLockColor.release();
            else
                mCameraOpenCloseLockMono.release();        }
    }

    /**
     * Start the camera preview.
     */
    private void startPreview(final int id) {
        if (null == mCameraDevices[id] || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        try {
            mPreviewBuilders[id] = mCameraDevices[id].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            List<Surface> outs = new ArrayList<>();
            if (id == COLOR_ID) {
                closePreviewSession(id);
                SurfaceTexture texture = mTextureView.getSurfaceTexture();
                assert texture != null;
                texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

                Surface previewSurface = new Surface(texture);
                mPreviewBuilders[id].addTarget(previewSurface);
                outs = Collections.singletonList(previewSurface);
            }
            else {
                mPreviewBuilders[id].addTarget(mImageReader.getSurface());
                outs = Collections.singletonList(mImageReader.getSurface());

            }
            mCameraDevices[id].createCaptureSession(outs,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mPreviewSessions[id] = session;
//                            linkBayerMono(id);
                            updatePreview(id);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Activity activity = getActivity();
                            if (null != activity) {
                                Toast.makeText(activity, "Failed " + session.getDevice(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    }, mBackgroundHandlers[id]);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Update the camera preview. {@link #startPreview()} needs to be called in advance.
     */
    private void updatePreview(int id) {
        if (null == mCameraDevices[id]) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilders[id]);
            HandlerThread thread = new HandlerThread("CameraPreview " + String.valueOf(id));
            thread.start();
            mPreviewSessions[id].setRepeatingRequest(mPreviewBuilders[id].build(), null, mBackgroundHandlers[id]);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private void setUpMediaRecorder(int id) throws IOException {
        final Activity activity = getActivity();
        if (null == activity) {
            return;
        }
//        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorders[id].setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorders[id].setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePaths[id] == null || mNextVideoAbsolutePaths[id].isEmpty()) {
            mNextVideoAbsolutePaths[id] = getVideoFilePath(getActivity(), id);
        }
        mMediaRecorders[id].setOutputFile(mNextVideoAbsolutePaths[id]);
        mMediaRecorders[id].setVideoEncodingBitRate(25_000_000);
        mMediaRecorders[id].setVideoSize(3840, 2160);
//        mediaRecorder.setCaptureRate(10);
//        mediaRecorder.setVideoFrameRate(10);
//        mediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
//        mMediaRecorders[id].setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
        mMediaRecorders[id].setVideoEncoder(MediaRecorder.VideoEncoder.H264);

//        mMediaRecorders[id].setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
//        switch (mSensorOrientation) {
//            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
//                mediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
//                break;
//            case SENSOR_ORIENTATION_INVERSE_DEGREES:
//                mediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
//                break;
//        }
        mMediaRecorders[id].prepare();
    }

    private String getVideoFilePath(Context context, int id) {
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + System.currentTimeMillis() + String.format("_%02d", id) +  ".mp4";
    }

    private void startRecordingOnRecorder(int id) {
        mMediaRecorders[id].start();

//        startRecordingOnRecorder(id);

    }
    private void startRecordingVideo(final int id) {
        if (null == mCameraDevices[id] || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        try {
            closePreviewSession(id);

            setUpMediaRecorder(id);
//            setUpMediaRecorder(mMediaRecorderMono);

            List<Surface> surfaces = new ArrayList<>();
            mPreviewBuilders[id] = mCameraDevices[id].createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            if (id == COLOR_ID) {
                SurfaceTexture texture = mTextureView.getSurfaceTexture();
                assert texture != null;
                texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                // Set up Surface for the camera preview
                Surface previewSurface = new Surface(texture);
                surfaces.add(previewSurface);
                mPreviewBuilders[id].addTarget(previewSurface);

            }

            // Set up Surface for the MediaRecorder
            Surface recorderSurface = mMediaRecorders[id].getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilders[id].addTarget(recorderSurface);

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            mCameraDevices[id].createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mPreviewSessions[id] = cameraCaptureSession;
                    if (id == COLOR_ID) {
                        updatePreview(id);
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // UI
                                mButtonVideo.setText(R.string.stop);
                                mIsRecordingVideo = true;

                                // Start recording
                                startRecordingOnRecorder(id);
//                                mMediaRecorders[id].start();
                            }
                        });
                    }
                    else {
                        updatePreview(id);

                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                startRecordingOnRecorder(id);
//                                mMediaRecorders[id].start();
                            }
                        });
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Activity activity = getActivity();
                    if (null != activity) {
                        Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }, mBackgroundHandlers[id]);
        } catch (CameraAccessException | IOException e) {
            e.printStackTrace();
        }

    }

    private void closePreviewSession(int id) {
        if (mPreviewSessions[id] != null) {
            mPreviewSessions[id].close();
            mPreviewSessions[id] = null;
        }
    }

    private void stopRecordingVideo(int id) {
        // UI
        if (id == COLOR_ID) {
            mIsRecordingVideo = false;
            mButtonVideo.setText(R.string.record);
        }
        // Stop recording
        mMediaRecorders[id].stop();

        Activity activity = getActivity();
        if (null != activity) {
            Toast.makeText(activity, "Video saved: " + mNextVideoAbsolutePaths[id],
                    Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Video saved: " + mNextVideoAbsolutePaths[id]);
        }
        mNextVideoAbsolutePaths[id] = null;
        if (id == COLOR_ID) {
            startPreview(id);
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    public static class ConfirmationDialog extends DialogFragment {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.permission_request)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            FragmentCompat.requestPermissions(parent, VIDEO_PERMISSIONS,
                                    REQUEST_VIDEO_PERMISSIONS);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    parent.getActivity().finish();
                                }
                            })
                    .create();
        }

    }

}
