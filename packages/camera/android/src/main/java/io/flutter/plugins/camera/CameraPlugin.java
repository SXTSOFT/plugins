package io.flutter.plugins.camera;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CameraPlugin implements MethodCallHandler {

  private static final int CAMERA_REQUEST_ID = 513469796;
  private static final String TAG = "CameraPlugin";

  private static CameraManager cameraManager;
  private final FlutterView view;
  private Camera camera;
  private Activity activity;
  private Registrar registrar;
  private Application.ActivityLifecycleCallbacks activityLifecycleCallbacks;
  // The code to run after requesting camera permissions.
  private Runnable cameraPermissionContinuation;
  private boolean requestingPermission;
  private final OrientationEventListener orientationEventListener;
  private int currentOrientation = ORIENTATION_UNKNOWN;

  private CameraPlugin(Registrar registrar, FlutterView view, Activity activity) {
    this.registrar = registrar;
    this.view = view;
    this.activity = activity;

    orientationEventListener =
        new OrientationEventListener(activity.getApplicationContext()) {
          @Override
          public void onOrientationChanged(int i) {
            if (i == ORIENTATION_UNKNOWN) {
              return;
            }
            // Convert the raw deg angle to the nearest multiple of 90.
            currentOrientation = (int) Math.round(i / 90.0) * 90;
          }
        };

    registrar.addRequestPermissionsResultListener(new CameraRequestPermissionsListener());

    this.activityLifecycleCallbacks =
        new Application.ActivityLifecycleCallbacks() {
          @Override
          public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

          @Override
          public void onActivityStarted(Activity activity) {}

          @Override
          public void onActivityResumed(Activity activity) {
            if (requestingPermission) {
              requestingPermission = false;
              return;
            }
            if (activity == CameraPlugin.this.activity) {
              orientationEventListener.enable();
              if (camera != null) {
                camera.open(null);
              }
            }
          }

          @Override
          public void onActivityPaused(Activity activity) {
            if (activity == CameraPlugin.this.activity) {
              orientationEventListener.disable();
              if (camera != null) {
                camera.close();
              }
            }
          }

          @Override
          public void onActivityStopped(Activity activity) {
            if (activity == CameraPlugin.this.activity) {
              if (camera != null) {
                camera.close();
              }
            }
          }

          @Override
          public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

          @Override
          public void onActivityDestroyed(Activity activity) {}
        };
  }

  public static void registerWith(Registrar registrar) {
    final MethodChannel channel =
        new MethodChannel(registrar.messenger(), "plugins.flutter.io/camera");

    cameraManager = (CameraManager) registrar.activity().getSystemService(Context.CAMERA_SERVICE);

    channel.setMethodCallHandler(
        new CameraPlugin(registrar, registrar.view(), registrar.activity()));
  }

  @Override
  public void onMethodCall(MethodCall call, final Result result) {
    switch (call.method) {
      case "init":
        if (camera != null) {
          camera.close();
        }
        result.success(null);
        break;
      case "availableCameras":
        try {
          String[] cameraNames = cameraManager.getCameraIdList();
          List<Map<String, Object>> cameras = new ArrayList<>();
          for (String cameraName : cameraNames) {
            HashMap<String, Object> details = new HashMap<>();
            CameraCharacteristics characteristics =
                cameraManager.getCameraCharacteristics(cameraName);
            details.put("name", cameraName);
            @SuppressWarnings("ConstantConditions")
            int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            switch (lensFacing) {
              case CameraMetadata.LENS_FACING_FRONT:
                details.put("lensFacing", "front");
                break;
              case CameraMetadata.LENS_FACING_BACK:
                details.put("lensFacing", "back");
                break;
              case CameraMetadata.LENS_FACING_EXTERNAL:
                details.put("lensFacing", "external");
                break;
            }
            cameras.add(details);
          }
          result.success(cameras);
        } catch (CameraAccessException e) {
          result.error("cameraAccess", e.getMessage(), null);
        }
        break;
      case "initialize":
        {
          String cameraName = call.argument("cameraName");
          String resolutionPreset = call.argument("resolutionPreset");
          if (camera != null) {
            camera.close();
          }
          camera = new Camera(cameraName, resolutionPreset, result);
          this.activity
              .getApplication()
              .registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks);
          orientationEventListener.enable();
          break;
        }
      case "takePicture":
        {
          camera.takePicture((String) call.argument("path"), result);
          break;
        }
      case "startVideoRecording":
        {
          final String filePath = call.argument("filePath");
          camera.startVideoRecording(filePath, result);
          break;
        }
      case "stopVideoRecording":
        {
          camera.stopVideoRecording(result);
          break;
        }
      case "dispose":
        {
          if (camera != null) {
            camera.dispose();
          }
          if (this.activity != null && this.activityLifecycleCallbacks != null) {
            this.activity
                .getApplication()
                .unregisterActivityLifecycleCallbacks(this.activityLifecycleCallbacks);
          }
          result.success(null);
          break;
        }
      case "setZoom":
        {
          try {
            double zoom = (double) call.argument("zoom");
            camera.setZoom((float)zoom);
            result.success(null);
          } catch (CameraAccessException e) {
            result.error("CameraAccess", e.getMessage(), null);
          }
          break;
        }
      case "focus": {
        int x = (int)call.argument("x");
        int y = (int)call.argument("y");
        camera.focus(x, y);
        result.success(null);
        break;
      }
      case "setFlashMode":{

        break;
      }
      default:
        result.notImplemented();
        break;
    }
  }

  private static class CompareSizesByArea implements Comparator<Size> {
    @Override
    public int compare(Size lhs, Size rhs) {
      // We cast here to ensure the multiplications won't overflow.
      return Long.signum(
          (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
    }
  }

  private class CameraRequestPermissionsListener
      implements PluginRegistry.RequestPermissionsResultListener {
    @Override
    public boolean onRequestPermissionsResult(int id, String[] permissions, int[] grantResults) {
      if (id == CAMERA_REQUEST_ID) {
        cameraPermissionContinuation.run();
        return true;
      }
      return false;
    }
  }

  private class Camera {
    private final FlutterView.SurfaceTextureEntry textureEntry;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private EventChannel.EventSink eventSink;
    private ImageReader imageReader;
    private int sensorOrientation;
    private boolean isFrontFacing;
    private String cameraName;
    private Size captureSize;
    private Size previewSize;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size videoSize;
    private MediaRecorder mediaRecorder;
    private boolean recordingVideo;
    public Rect zoom;
    protected CameraCharacteristics cameraCharacteristics;

    Camera(final String cameraName, final String resolutionPreset, final Result result) {

      this.cameraName = cameraName;
      textureEntry = view.createSurfaceTexture();

      registerEventChannel();

      try {
        Size minPreviewSize;
        switch (resolutionPreset) {
          case "high":
            minPreviewSize = new Size(1024, 768);
            break;
          case "medium":
            minPreviewSize = new Size(640, 480);
            break;
          case "low":
            minPreviewSize = new Size(320, 240);
            break;
          default:
            throw new IllegalArgumentException("Unknown preset: " + resolutionPreset);
        }

        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
        StreamConfigurationMap streamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        cameraCharacteristics = characteristics;
        //noinspection ConstantConditions
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        //noinspection ConstantConditions
        isFrontFacing =
            characteristics.get(CameraCharacteristics.LENS_FACING)
                == CameraMetadata.LENS_FACING_FRONT;
        computeBestCaptureSize(streamConfigurationMap);
        computeBestPreviewAndRecordingSize(streamConfigurationMap, minPreviewSize, captureSize);

        if (cameraPermissionContinuation != null) {
          result.error("cameraPermission", "Camera permission request ongoing", null);
        }
        cameraPermissionContinuation =
            new Runnable() {
              @Override
              public void run() {
                cameraPermissionContinuation = null;
                if (!hasCameraPermission()) {
                  result.error(
                      "cameraPermission", "MediaRecorderCamera permission not granted", null);
                  return;
                }
                if (!hasAudioPermission()) {
                  result.error(
                      "cameraPermission", "MediaRecorderAudio permission not granted", null);
                  return;
                }
                open(result);
              }
            };
        requestingPermission = false;
        if (hasCameraPermission() && hasAudioPermission()) {
          cameraPermissionContinuation.run();
        } else {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestingPermission = true;
            registrar
                .activity()
                .requestPermissions(
                    new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    CAMERA_REQUEST_ID);
          }
        }
      } catch (CameraAccessException e) {
        result.error("CameraAccess", e.getMessage(), null);
      } catch (IllegalArgumentException e) {
        result.error("IllegalArgumentException", e.getMessage(), null);
      }
    }

    private void registerEventChannel() {
      new EventChannel(
              registrar.messenger(), "flutter.io/cameraPlugin/cameraEvents" + textureEntry.id())
          .setStreamHandler(
              new EventChannel.StreamHandler() {
                @Override
                public void onListen(Object arguments, EventChannel.EventSink eventSink) {
                  Camera.this.eventSink = eventSink;
                }

                @Override
                public void onCancel(Object arguments) {
                  Camera.this.eventSink = null;
                }
              });
    }

    private boolean hasCameraPermission() {
      return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
          || activity.checkSelfPermission(Manifest.permission.CAMERA)
              == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
      return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
          || registrar.activity().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
              == PackageManager.PERMISSION_GRANTED;
    }

    private void computeBestPreviewAndRecordingSize(
        StreamConfigurationMap streamConfigurationMap, Size minPreviewSize, Size captureSize) {
      Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);

      // Preview size and video size should not be greater than screen resolution or 1080.
      Point screenResolution = new Point();
      Display display = activity.getWindowManager().getDefaultDisplay();
      display.getRealSize(screenResolution);

      final boolean swapWH = getMediaOrientation() % 180 == 90;
      int screenWidth = swapWH ? screenResolution.y : screenResolution.x;
      int screenHeight = swapWH ? screenResolution.x : screenResolution.y;

      List<Size> goodEnough = new ArrayList<>();
      for (Size s : sizes) {
        if (minPreviewSize.getWidth() < s.getWidth()
            && minPreviewSize.getHeight() < s.getHeight()
            && s.getWidth() <= screenWidth
            && s.getHeight() <= screenHeight
            && s.getHeight() <= 1080) {
          goodEnough.add(s);
        }
      }

      Collections.sort(goodEnough, new CompareSizesByArea());

      if (goodEnough.isEmpty()) {
        previewSize = sizes[0];
        videoSize = sizes[0];
      } else {
        float captureSizeRatio = (float) captureSize.getWidth() / captureSize.getHeight();

        previewSize = goodEnough.get(0);
        for (Size s : goodEnough) {
          if ((float) s.getWidth() / s.getHeight() == captureSizeRatio) {
            previewSize = s;
            break;
          }
        }

        Collections.reverse(goodEnough);
        videoSize = goodEnough.get(0);
        for (Size s : goodEnough) {
          if ((float) s.getWidth() / s.getHeight() == captureSizeRatio) {
            videoSize = s;
            break;
          }
        }
      }
    }

    private void computeBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
      // For still image captures, we use the largest available size.
      captureSize =
          Collections.max(
              Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)),
              new CompareSizesByArea());
    }

    private void prepareMediaRecorder(String outputFilePath) throws IOException {
      if (mediaRecorder != null) {
        mediaRecorder.release();
      }
      mediaRecorder = new MediaRecorder();
      mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
      mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
      mediaRecorder.setVideoEncodingBitRate(1024 * 1000);
      mediaRecorder.setAudioSamplingRate(16000);
      mediaRecorder.setVideoFrameRate(27);
      mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
      mediaRecorder.setOutputFile(outputFilePath);
      mediaRecorder.setOrientationHint(getMediaOrientation());

      mediaRecorder.prepare();
    }

    private void open(final Result result) {
      if (!hasCameraPermission()) {
        if (result != null) result.error("cameraPermission", "Camera permission not granted", null);
      } else {
        try {
          imageReader =
              ImageReader.newInstance(
                  captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);
          cameraManager.openCamera(
              cameraName,
              new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cameraDevice) {
                  Camera.this.cameraDevice = cameraDevice;
                  try {
                    startPreview();
                  } catch (CameraAccessException e) {
                    if (result != null) result.error("CameraAccess", e.getMessage(), null);
                  }

                  if (result != null) {
                    Map<String, Object> reply = new HashMap<>();
                    reply.put("textureId", textureEntry.id());
                    reply.put("previewWidth", previewSize.getWidth());
                    reply.put("previewHeight", previewSize.getHeight());
                    result.success(reply);
                  }
                }

                @Override
                public void onClosed(CameraDevice camera) {
                  if (eventSink != null) {
                    Map<String, String> event = new HashMap<>();
                    event.put("eventType", "cameraClosing");
                    eventSink.success(event);
                  }
                  super.onClosed(camera);
                }

                @Override
                public void onDisconnected(CameraDevice cameraDevice) {
                  cameraDevice.close();
                  Camera.this.cameraDevice = null;
                  sendErrorEvent("The camera was disconnected.");
                }

                @Override
                public void onError(CameraDevice cameraDevice, int errorCode) {
                  cameraDevice.close();
                  Camera.this.cameraDevice = null;
                  String errorDescription;
                  switch (errorCode) {
                    case ERROR_CAMERA_IN_USE:
                      errorDescription = "The camera device is in use already.";
                      break;
                    case ERROR_MAX_CAMERAS_IN_USE:
                      errorDescription = "Max cameras in use";
                      break;
                    case ERROR_CAMERA_DISABLED:
                      errorDescription =
                          "The camera device could not be opened due to a device policy.";
                      break;
                    case ERROR_CAMERA_DEVICE:
                      errorDescription = "The camera device has encountered a fatal error";
                      break;
                    case ERROR_CAMERA_SERVICE:
                      errorDescription = "The camera service has encountered a fatal error.";
                      break;
                    default:
                      errorDescription = "Unknown camera error";
                  }
                  sendErrorEvent(errorDescription);
                }
              },
              null);
        } catch (CameraAccessException e) {
          if (result != null) result.error("cameraAccess", e.getMessage(), null);
        }
      }
    }

    private void writeToFile(ByteBuffer buffer, File file) throws IOException {
      try (FileOutputStream outputStream = new FileOutputStream(file)) {
        while (0 < buffer.remaining()) {
          outputStream.getChannel().write(buffer);
        }
      }
    }

    private void takePicture(String filePath, final Result result) {
      final File file = new File(filePath);

      if (file.exists()) {
        result.error(
            "fileExists",
            "File at path '" + filePath + "' already exists. Cannot overwrite.",
            null);
        return;
      }

      imageReader.setOnImageAvailableListener(
          new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
              try (Image image = reader.acquireLatestImage()) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                writeToFile(buffer, file);
                result.success(null);
              } catch (IOException e) {
                result.error("IOError", "Failed saving image", null);
              }
            }
          },
          null);

      try {
        final CaptureRequest.Builder captureBuilder =
            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(imageReader.getSurface());
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());
        setScalerCropRegion(captureBuilder);
        cameraCaptureSession.capture(
            captureBuilder.build(),
            new CameraCaptureSession.CaptureCallback() {
              @Override
              public void onCaptureFailed(
                  CameraCaptureSession session,
                  CaptureRequest request,
                  CaptureFailure failure) {
                String reason;
                switch (failure.getReason()) {
                  case CaptureFailure.REASON_ERROR:
                    reason = "An error happened in the framework";
                    break;
                  case CaptureFailure.REASON_FLUSHED:
                    reason = "The capture has failed due to an abortCaptures() call";
                    break;
                  default:
                    reason = "Unknown reason";
                }
                result.error("captureFailure", reason, null);
              }
            },
            null);
      } catch (CameraAccessException e) {
        result.error("cameraAccess", e.getMessage(), null);
      }
    }

    private void startVideoRecording(String filePath, final Result result) {
      if (cameraDevice == null) {
        result.error("configureFailed", "Camera was closed during configuration.", null);
        return;
      }
      if (new File(filePath).exists()) {
        result.error(
            "fileExists",
            "File at path '" + filePath + "' already exists. Cannot overwrite.",
            null);
        return;
      }
      try {
        closeCaptureSession();
        prepareMediaRecorder(filePath);

        recordingVideo = true;

        SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

        List<Surface> surfaces = new ArrayList<>();

        Surface previewSurface = new Surface(surfaceTexture);
        surfaces.add(previewSurface);
        captureRequestBuilder.addTarget(previewSurface);

        Surface recorderSurface = mediaRecorder.getSurface();
        surfaces.add(recorderSurface);
        captureRequestBuilder.addTarget(recorderSurface);

        cameraDevice.createCaptureSession(
            surfaces,
            new CameraCaptureSession.StateCallback() {
              @Override
              public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                try {
                  if (cameraDevice == null) {
                    result.error("configureFailed", "Camera was closed during configuration", null);
                    return;
                  }
                  Camera.this.cameraCaptureSession = cameraCaptureSession;
                  captureRequestBuilder.set(
                      CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                  setScalerCropRegion(captureRequestBuilder);
                  cameraCaptureSession.setRepeatingRequest(
                      captureRequestBuilder.build(), null, null);
                  mediaRecorder.start();
                  result.success(null);
                } catch (CameraAccessException e) {
                  result.error("cameraAccess", e.getMessage(), null);
                }
              }

              @Override
              public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                result.error("configureFailed", "Failed to configure camera session", null);
              }
            },
            null);
      } catch (CameraAccessException | IOException e) {
        result.error("videoRecordingFailed", e.getMessage(), null);
      }
    }

    private void stopVideoRecording(final Result result) {
      if (!recordingVideo) {
        result.success(null);
        return;
      }

      try {
        recordingVideo = false;
        mediaRecorder.stop();
        mediaRecorder.reset();
        startPreview();
        result.success(null);
      } catch (CameraAccessException | IllegalStateException e) {
        result.error("videoRecordingFailed", e.getMessage(), null);
      }
    }

    private void startPreview() throws CameraAccessException {
      closeCaptureSession();

      SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
      surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
      captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

      List<Surface> surfaces = new ArrayList<>();

      Surface previewSurface = new Surface(surfaceTexture);
      surfaces.add(previewSurface);
      captureRequestBuilder.addTarget(previewSurface);

      surfaces.add(imageReader.getSurface());

      cameraDevice.createCaptureSession(
          surfaces,
          new CameraCaptureSession.StateCallback() {

            @Override
            public void onConfigured(CameraCaptureSession session) {
              if (cameraDevice == null) {
                sendErrorEvent("The camera was closed during configuration.");
                return;
              }
              try {
                cameraCaptureSession = session;
                captureRequestBuilder.set(
                    CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                setScalerCropRegion(captureRequestBuilder);
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
              } catch (CameraAccessException e) {
                sendErrorEvent(e.getMessage());
              }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
              sendErrorEvent("Failed to configure the camera for preview.");
            }
          },
          null);
    }

    private void sendErrorEvent(String errorDescription) {
      if (eventSink != null) {
        Map<String, String> event = new HashMap<>();
        event.put("eventType", "error");
        event.put("errorDescription", errorDescription);
        eventSink.success(event);
      }
    }

    private void closeCaptureSession() {
      if (cameraCaptureSession != null) {
        cameraCaptureSession.close();
        cameraCaptureSession = null;
      }
    }

    private void close() {
      closeCaptureSession();

      if (cameraDevice != null) {
        cameraDevice.close();
        cameraDevice = null;
      }
      if (imageReader != null) {
        imageReader.close();
        imageReader = null;
      }
      if (mediaRecorder != null) {
        mediaRecorder.reset();
        mediaRecorder.release();
        mediaRecorder = null;
      }
    }

    private void dispose() {
      close();
      textureEntry.release();
    }

    private int getMediaOrientation() {
      final int sensorOrientationOffset =
          (isFrontFacing) ? -currentOrientation : currentOrientation;
      return (sensorOrientationOffset + sensorOrientation + 360) % 360;
    }

    private void setZoom(float step) throws CameraAccessException {
      Rect rect = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

      float ratio = (float) 1 / step;
      int croppedWidth = rect.width() - Math.round((float) rect.width() * ratio);
      int croppedHeight = rect.height() - Math.round((float) rect.height() * ratio);
      zoom = new Rect(croppedWidth / 2, croppedHeight / 2,
              rect.width() - croppedWidth / 2, rect.height() - croppedHeight / 2);


      setScalerCropRegion(captureRequestBuilder);
      cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
    }



    private void focus(int x, int y){
      MeteringRectangle[] rectangle = calculateFocusRect(x, y);
      // 对焦模式必须设置为AUTO
      captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_AUTO);
      //AE
      captureRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS,rectangle);
      //AF 此处AF和AE用的同一个rect, 实际AE矩形面积比AF稍大, 这样测光效果更好
      captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,rectangle);
      try {
        // AE/AF区域设置通过setRepeatingRequest不断发请求
        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
      } catch (CameraAccessException e) {
        e.printStackTrace();
      }
      //触发对焦
      captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
      try {
        //触发对焦通过capture发送请求, 因为用户点击屏幕后只需触发一次对焦
        cameraCaptureSession.capture(captureRequestBuilder.build(), null, null);
      } catch (CameraAccessException e) {
        e.printStackTrace();
      }
    }
    private MeteringRectangle[] calculateFocusRect(int x, int y) {
      //Size of the Rectangle.
      int areaSize = 200;

      int left = clamp((int) x - areaSize / 2, 0, previewSize.getWidth() - areaSize);
      int top = clamp((int) y - areaSize / 2, 0, previewSize.getHeight() - areaSize);

      RectF rectF = new RectF(left, top, left + areaSize, top + areaSize);
      Rect focusRect = new Rect(Math.round(rectF.left), Math.round(rectF.top), Math.round(rectF.right), Math.round(rectF.bottom));
      MeteringRectangle meteringRectangle = new MeteringRectangle(focusRect, 1);

      return new MeteringRectangle[] {meteringRectangle};
    }

    //Clamp the inputs.
    private int clamp(int x, int min, int max) {
      if (x > max) {
        return max;
      }
      if (x < min) {
        return min;
      }
      return x;
    }


    private void setScalerCropRegion(CaptureRequest.Builder captureRequestBuilder) {
      captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);

    }

  }
}
