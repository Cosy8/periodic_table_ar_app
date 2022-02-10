/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package periodictable.augmentedimage;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import periodictable.augmentedimage.rendering.AugmentedImageRenderer;
import periodictable.common.helpers.CameraPermissionHelper;
import periodictable.common.helpers.DisplayRotationHelper;
import periodictable.common.helpers.FullScreenHelper;
import periodictable.common.helpers.SnackbarHelper;
import periodictable.common.helpers.TrackingStateHelper;
import periodictable.common.rendering.BackgroundRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This app extends the HelloAR Java app to include image tracking functionality.
 *
 * <p>In this example, we assume all images are static or moving slowly with a large occupation of
 * the screen. If the target is actively moving, we recommend to check
 * AugmentedImage.getTrackingMethod() and render only when the tracking method equals to
 * FULL_TRACKING. See details in <a
 * href="https://developers.google.com/ar/develop/java/augmented-images/">Recognize and Augment
 * Images</a>.
 */
public class AugmentedImageActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = AugmentedImageActivity.class.getSimpleName();
  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;
  private ImageView fitToScanView;
  private RequestManager glideRequestManager;
  private GestureDetector mGestureDetector;

  private boolean installRequested;

  private Session session;
  private Frame frame;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final AugmentedImageRenderer augmentedImageRenderer = new AugmentedImageRenderer();

  private boolean shouldConfigureSession = false;

  int viewWidth = 0;
  int viewHeight = 0;

  // Augmented image configuration and rendering.
  // Load a single image (true) or a pre-generated image database (false).
  private final boolean useSingleImage = false;
  // Augmented image and its associated center pose anchor, keyed by index of the augmented image in
  // the
  // database.
  private final Map<Integer, Pair<AugmentedImage, Anchor>> augmentedImageMap = new HashMap<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    // Set up tap listener.
    mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onSingleTapUp(MotionEvent e) {
        try {
          onSingleTap(e);
        } catch (CameraNotAvailableException | IOException exception) {
          exception.printStackTrace();
        }
        return true;
      }

      @Override
      public boolean onDown(MotionEvent e) {
        return true;
      }
    });

    surfaceView.setOnTouchListener(new View.OnTouchListener() {
      @Override
      public boolean onTouch(View v, MotionEvent event) {
        return mGestureDetector.onTouchEvent(event);
      }
    });

    // Set up renderer.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    fitToScanView = findViewById(R.id.image_view_fit_to_scan);
    glideRequestManager = Glide.with(this);
    glideRequestManager
        .load(Uri.parse("file:///android_asset/fit_to_scan.png"))
        .into(fitToScanView);

    installRequested = false;

    // When the start button is clicked, remove the start page.
    Button button = (Button) findViewById(R.id.button);
    button.setOnClickListener(new View.OnClickListener() {
      public void onClick(View v) {
        findViewById(R.id.start_page).setVisibility(View.GONE);
      }
    });
  }

  private void onSingleTap(MotionEvent e) throws CameraNotAvailableException, IOException {
    boolean isHit = false;
    Camera camera = null;

    // Get current session and frame
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    if (session == null) {
      return;
    }
    displayRotationHelper.updateSessionIfNeeded(session);
    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());
      camera = frame.getCamera();
    }
    catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on tap event", t);
    }

    for (Map.Entry<Integer, Pair<AugmentedImage, Anchor>> augImageEntry : augmentedImageMap.entrySet()){
      AugmentedImage augImage = (AugmentedImage) augImageEntry.getValue().first;
      Pose center = augImage.getCenterPose();

      float[] mAnchorMatrix = new float[100];
      float[] centerVertexOf3dObject = {0f, 0f, 0f, 1};
      float[] vertexResult = new float[4];
      float[] projmtx = new float[16];
      float[] viewmtx = new float[16];
      final float[] colorCorrectionRgba = new float[4];

      // Get projection matrix.
      camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);
      // Get camera matrix and draw
      camera.getViewMatrix(viewmtx, 0);
      // Compute lighting from average intensity of the image.
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      center.toMatrix(mAnchorMatrix, 0);
      augmentedImageRenderer.cardObject.updateModelMatrix(mAnchorMatrix, 1);
      augmentedImageRenderer.cardObject.draw(viewmtx, projmtx, colorCorrectionRgba);

      Matrix.multiplyMV(vertexResult, 0,
              augmentedImageRenderer.cardObject.getModelViewProjectionMatrix(), 0,
              centerVertexOf3dObject, 0);

      //Math to check tap hit on object
      float cardHitAreaRadius = augImage.getExtentX();
      float radius = (viewWidth / 2) * (cardHitAreaRadius / vertexResult[3]);
      float dx = e.getX() - (viewWidth / 2) * (1 + vertexResult[0] / vertexResult[3]);
      float dy = e.getY() - (viewHeight / 2) * (1 - vertexResult[1] / vertexResult[3]);
      double distance = Math.sqrt(dx * dx + dy * dy);
      isHit = distance < radius;

      if (isHit) {
        Log.i(TAG, "Tap hit on " + augImage.getName());
        change_texture(augImage, augmentedImageRenderer.cardObject.current_texture_name);
      }
    }

    //messageSnackbarHelper.showMessage(this, "hit?" + isHit);
  }

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        session = new Session(/* context = */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (Exception e) {
        message = "This device does not support AR";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }

      shouldConfigureSession = true;
    }

    if (shouldConfigureSession) {
      configureSession();
      shouldConfigureSession = false;
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }
    surfaceView.onResume();
    displayRotationHelper.onResume();

    fitToScanView.setVisibility(View.VISIBLE);
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(
              this, "Camera permissions are needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(/*context=*/ this);
      augmentedImageRenderer.createOnGlThread(/*context=*/ this, "models/textures/template.png");
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
    viewWidth = width;
    viewHeight = height;
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      frame = session.update();
      Camera camera = frame.getCamera();

      // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
      trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame);

      // Get projection matrix.
      float[] projmtx = new float[16];
      camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

      // Get camera matrix and draw.
      float[] viewmtx = new float[16];
      camera.getViewMatrix(viewmtx, 0);

      // Compute lighting from average intensity of the image.
      final float[] colorCorrectionRgba = new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      // Visualize augmented images.
      drawAugmentedImages(frame, projmtx, viewmtx, colorCorrectionRgba);
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  private void configureSession() {
    Config config = new Config(session);
    config.setFocusMode(Config.FocusMode.AUTO);
    if (!setupAugmentedImageDatabase(config)) {
      messageSnackbarHelper.showError(this, "Could not setup augmented image database");
    }
    session.configure(config);
  }

  private void drawAugmentedImages(
      Frame frame, float[] projmtx, float[] viewmtx, float[] colorCorrectionRgba) throws IOException {
    Collection<AugmentedImage> updatedAugmentedImages =
        frame.getUpdatedTrackables(AugmentedImage.class);

    // Iterate to update augmentedImageMap, remove elements we cannot draw.
    for (AugmentedImage augmentedImage : updatedAugmentedImages) {
      switch (augmentedImage.getTrackingState()) {
        case PAUSED:
          // When an image is in PAUSED state, but the camera is not PAUSED, it has been detected,
          // but not yet tracked.
          //String text = String.format("Detected Image: %s", augmentedImage.getName());
          //messageSnackbarHelper.showMessage(this, text);
          break;

        case TRACKING:
          if(augmentedImage.getTrackingMethod()==AugmentedImage.TrackingMethod.FULL_TRACKING) {
            // messageSnackbarHelper.showMessage(this, "Full tracking");

            // Have to switch to UI Thread to update View.
            this.runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        fitToScanView.setVisibility(View.GONE);
                      }
                    });

            // Create a new anchor for newly found images.
            if (!augmentedImageMap.containsKey(augmentedImage.getIndex())) {
              Anchor centerPoseAnchor = augmentedImage.createAnchor(augmentedImage.getCenterPose());
              augmentedImageMap.put(
                      augmentedImage.getIndex(), Pair.create(augmentedImage, centerPoseAnchor));
            }
          }
          else{
            // messageSnackbarHelper.showMessage(this, "Not full tracking");
            augmentedImageMap.remove(augmentedImage.getIndex());
          }
          break;

        case STOPPED:
          augmentedImageMap.remove(augmentedImage.getIndex());
          break;

        default:
          break;
      }
    }

    // Draw all images in augmentedImageMap
    for (Pair<AugmentedImage, Anchor> pair : augmentedImageMap.values()) {
      AugmentedImage augmentedImage = pair.first;
      Anchor centerAnchor = augmentedImageMap.get(augmentedImage.getIndex()).second;
      switch (augmentedImage.getTrackingState()) {
        case TRACKING:
          if(augmentedImage.getTrackingMethod()==AugmentedImage.TrackingMethod.FULL_TRACKING) {
            //String text = String.format("Detected Image: %s", augmentedImage.getName());
            //messageSnackbarHelper.showMessage(this, text);

            try {
              if (augmentedImageRenderer.cardObject.current_texture_name == "info" || augmentedImageRenderer.cardObject.current_texture_name == "default") {
                Bitmap textureBitmap =
                        BitmapFactory.decodeStream(this.getAssets().open(String.format("models/textures/element_info/%s", augmentedImage.getName())));
                augmentedImageRenderer.cardObject.setTextureOnGLThread(textureBitmap, "info");
              }
              else {
                Bitmap textureBitmap =
                        BitmapFactory.decodeStream(this.getAssets().open(String.format("models/textures/element_pictures/%s", augmentedImage.getName())));
                augmentedImageRenderer.cardObject.setTextureOnGLThread(textureBitmap, "picture");
              }
            }
            catch (IOException e) {
              Bitmap textureBitmap =
                      BitmapFactory.decodeStream(this.getAssets().open("models/textures/template.png"));
              augmentedImageRenderer.cardObject.setTextureOnGLThread(textureBitmap, "default");
            }

            augmentedImageRenderer.draw(
                    viewmtx, projmtx, augmentedImage, centerAnchor, colorCorrectionRgba);
          }
          break;
        default:
          break;
      }
    }
  }

  private boolean change_texture(AugmentedImage augmentedImage, String current_texture) throws IOException {
    String texture;
    String new_texture_name;

    if (current_texture == "info") {
      texture = String.format("models/textures/element_pictures/%s", augmentedImage.getName());
      new_texture_name = "picture";
    }
    else {
      texture = String.format("models/textures/element_info/%s", augmentedImage.getName());
      new_texture_name = "info";
    }

    // Change the texture in realtime while drawing
    Bitmap textureBitmap =
            BitmapFactory.decodeStream(this.getAssets().open(texture));
    augmentedImageRenderer.cardObject.setTextureOnGLThread(textureBitmap, new_texture_name);

    Log.i(TAG, "Texture changed to: " + texture);
    return true;
  }

  private boolean setupAugmentedImageDatabase(Config config) {
    AugmentedImageDatabase augmentedImageDatabase;

    // There are two ways to configure an AugmentedImageDatabase:
    // 1. Add Bitmap to DB directly
    // 2. Load a pre-built AugmentedImageDatabase
    // Option 2) has
    // * shorter setup time
    // * doesn't require images to be packaged in apk.
    if (useSingleImage) {
      Bitmap augmentedImageBitmap = loadAugmentedImageBitmap();
      if (augmentedImageBitmap == null) {
        return false;
      }

      augmentedImageDatabase = new AugmentedImageDatabase(session);
      augmentedImageDatabase.addImage("image_name", augmentedImageBitmap);
    } else {
      // NewCellDatabase/Photoshopped/NewCellDatabase.imgdb
      // periodic_table_pictures/periodic_table_db.imgdb
      try (InputStream is = getAssets().open("NewCellDatabase/Photoshopped/NewCellDatabase.imgdb")) {
        augmentedImageDatabase = AugmentedImageDatabase.deserialize(session, is);
      } catch (IOException e) {
        Log.e(TAG, "IO exception loading augmented image database.", e);
        return false;
      }
    }

    config.setAugmentedImageDatabase(augmentedImageDatabase);
    return true;
  }

  private Bitmap loadAugmentedImageBitmap() {
    try (InputStream is = getAssets().open("default.jpg")) {
      return BitmapFactory.decodeStream(is);
    } catch (IOException e) {
      Log.e(TAG, "IO exception loading augmented image bitmap.", e);
    }
    return null;
  }
}
