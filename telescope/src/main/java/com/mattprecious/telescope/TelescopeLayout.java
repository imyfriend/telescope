package com.mattprecious.telescope;

import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Process;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

import static android.Manifest.permission.VIBRATE;
import static android.animation.ValueAnimator.AnimatorUpdateListener;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.graphics.Paint.Style;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.LOLLIPOP;

/**
 * A layout used to take a screenshot and initiate a callback when the user long-presses the
 * container.
 */
public class TelescopeLayout extends FrameLayout {
  private static final String TAG = "Telescope";
  private static final SimpleDateFormat SCREENSHOT_FILE_FORMAT =
      new SimpleDateFormat("'telescope'-yyyy-MM-dd-HHmmss.'png'");
  private static final int PROGRESS_STROKE_DP = 4;
  private static final long CANCEL_DURATION_MS = 250;
  private static final long DONE_DURATION_MS = 1000;
  private static final long TRIGGER_DURATION_MS = 1000;
  private static final long VIBRATION_DURATION_MS = 50;

  private static final int DEFAULT_POINTER_COUNT = 2;
  private static final int DEFAULT_PROGRESS_COLOR = 0xff33b5e5;

  private final MediaProjectionManager projectionManager;
  private final WindowManager windowManager;
  private final Vibrator vibrator;
  private final Handler handler = new Handler();
  private final Runnable trigger = new Runnable() {
    @Override public void run() {
      trigger();
    }
  };
  private final IntentFilter requestCaptureFilter;
  private final BroadcastReceiver requestCaptureReceiver;

  private final float halfStrokeWidth;
  private final File screenshotFolder;
  private final Paint progressPaint;
  private final ValueAnimator progressAnimator;
  private final ValueAnimator progressCancelAnimator;
  private final ValueAnimator doneAnimator;

  private Lens lens;
  private View screenshotTarget;
  private int pointerCount;
  private ScreenshotMode screenshotMode;
  private boolean screenshotChildrenOnly;
  private boolean vibrate;

  // State.
  private float progressFraction;
  private float doneFraction;
  private boolean pressing;
  private boolean capturing;
  private boolean saving;

  public TelescopeLayout(Context context) {
    this(context, null);
  }

  public TelescopeLayout(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public TelescopeLayout(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    setWillNotDraw(false);
    screenshotTarget = this;

    float density = context.getResources().getDisplayMetrics().density;
    halfStrokeWidth = PROGRESS_STROKE_DP * density / 2;

    TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TelescopeLayout, defStyle, 0);
    pointerCount = a.getInt(R.styleable.TelescopeLayout_pointerCount, DEFAULT_POINTER_COUNT);
    int progressColor =
        a.getColor(R.styleable.TelescopeLayout_progressColor, DEFAULT_PROGRESS_COLOR);
    screenshotMode = ScreenshotMode.values()[a.getInt(R.styleable.TelescopeLayout_screenshotMode,
        ScreenshotMode.SYSTEM.ordinal())];
    screenshotChildrenOnly =
        a.getBoolean(R.styleable.TelescopeLayout_screenshotChildrenOnly, false);
    vibrate = a.getBoolean(R.styleable.TelescopeLayout_vibrate, true);
    a.recycle();

    progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    progressPaint.setColor(progressColor);
    progressPaint.setStrokeWidth(PROGRESS_STROKE_DP * density);
    progressPaint.setStyle(Style.STROKE);

    AnimatorUpdateListener progressUpdateListener = new AnimatorUpdateListener() {
      @Override public void onAnimationUpdate(ValueAnimator animation) {
        progressFraction = (float) animation.getAnimatedValue();
        invalidate();
      }
    };

    progressAnimator = new ValueAnimator();
    progressAnimator.setDuration(TRIGGER_DURATION_MS);
    progressAnimator.addUpdateListener(progressUpdateListener);

    progressCancelAnimator = new ValueAnimator();
    progressCancelAnimator.setDuration(CANCEL_DURATION_MS);
    progressCancelAnimator.addUpdateListener(progressUpdateListener);

    doneFraction = 1;
    doneAnimator = ValueAnimator.ofFloat(0, 1);
    doneAnimator.setDuration(DONE_DURATION_MS);
    doneAnimator.addUpdateListener(new AnimatorUpdateListener() {
      @Override public void onAnimationUpdate(ValueAnimator animation) {
        doneFraction = (float) animation.getAnimatedValue();
        invalidate();
      }
    });

    if (isInEditMode()) {
      projectionManager = null;
      windowManager = null;
      vibrator = null;
      screenshotFolder = null;
      requestCaptureFilter = null;
      requestCaptureReceiver = null;
      return;
    }

    windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    screenshotFolder = getScreenshotFolder(context);

    if (SDK_INT < LOLLIPOP) {
      projectionManager = null;
      requestCaptureFilter = null;
      requestCaptureReceiver = null;
    } else {
      projectionManager =
          (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);

      requestCaptureFilter =
          new IntentFilter(RequestCaptureActivity.getResultBroadcastAction(context));
      requestCaptureReceiver = new BroadcastReceiver() {
        @TargetApi(Build.VERSION_CODES.LOLLIPOP) @Override
        public void onReceive(Context context, Intent intent) {
          unregisterRequestCaptureReceiver();

          int resultCode = intent.getIntExtra(RequestCaptureActivity.RESULT_EXTRA_CODE,
              Activity.RESULT_CANCELED);
          Intent data = intent.getParcelableExtra(RequestCaptureActivity.RESULT_EXTRA_DATA);

          final MediaProjection mediaProjection =
              projectionManager.getMediaProjection(resultCode, data);
          if (mediaProjection == null) {
            captureCanvasScreenshot();
            return;
          }

          // Delay capture until after the permission dialog is gone.
          postDelayed(new Runnable() {
            @Override public void run() {
              captureNativeScreenshot(mediaProjection);
            }
          }, 1000);
        }
      };
    }
  }

  /**
   * Delete the screenshot folder for this app. Be careful not to call this before any intents have
   * finished using a screenshot reference.
   */
  public static void cleanUp(Context context) {
    File path = getScreenshotFolder(context);
    if (!path.exists()) {
      return;
    }

    delete(path);
  }

  /** Set the {@link Lens} to be called when the user triggers a capture. */
  public void setLens(Lens lens) {
    this.lens = lens;
  }

  /** Set the number of pointers requires to trigger the capture. Default is 2. */
  public void setPointerCount(int pointerCount) {
    this.pointerCount = pointerCount;
  }

  /** Set the color of the progress bars. */
  public void setProgressColor(int progressColor) {
    progressPaint.setColor(progressColor);
  }

  /** Sets the {@link ScreenshotMode} used to capture a screenshot. */
  public void setScreenshotMode(ScreenshotMode screenshotMode) {
    this.screenshotMode = screenshotMode;
  }

  /**
   * Set whether the screenshot will capture the children of this view only, or if it will
   * capture the whole window this view is in. Default is false.
   */
  public void setScreenshotChildrenOnly(boolean screenshotChildrenOnly) {
    this.screenshotChildrenOnly = screenshotChildrenOnly;
  }

  /** Set the target view that the screenshot will capture. */
  public void setScreenshotTarget(View screenshotTarget) {
    this.screenshotTarget = screenshotTarget;
  }

  /**
   * <p>Set whether vibration is enabled when a capture is triggered. Default is true.</p>
   *
   * <p><i>Requires the {@link android.Manifest.permission#VIBRATE} permission.</i></p>
   */
  public void setVibrate(boolean vibrate) {
    this.vibrate = vibrate;
  }

  @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
    if (!isEnabled()) {
      return false;
    }

    // Capture all clicks while capturing/saving.
    if (capturing || saving) {
      return true;
    }

    if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
        && ev.getPointerCount() == pointerCount) {
      // onTouchEvent isn't called if we steal focus from a child, so call start here.
      start();

      // Steal the events from our children.
      return true;
    }

    return super.onInterceptTouchEvent(ev);
  }

  @Override public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled()) {
      return false;
    }

    // Capture all clicks while capturing/saving.
    if (capturing || saving) {
      return true;
    }

    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_POINTER_UP:
        if (pressing) {
          cancel();
        }

        return false;
      case MotionEvent.ACTION_DOWN:
        if (!pressing && event.getPointerCount() == pointerCount) {
          start();
        }
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
        if (event.getPointerCount() == pointerCount) {
          // There's a few cases where we'll get called called in both onInterceptTouchEvent and
          // here, so make sure we only start once.
          if (!pressing) {
            start();
          }
          return true;
        } else {
          cancel();
        }
        break;
      case MotionEvent.ACTION_MOVE:
        if (pressing) {
          invalidate();
          return true;
        }
        break;
    }

    return super.onTouchEvent(event);
  }

  @Override public void draw(Canvas canvas) {
    super.draw(canvas);

    // Do not draw any bars while we're capturing a screenshot.
    if (capturing) {
      return;
    }

    int width = getMeasuredWidth();
    int height = getMeasuredHeight();

    float fraction = saving ? 1 : progressFraction;
    if (fraction > 0) {
      // Top (left to right).
      canvas.drawLine(0, halfStrokeWidth, width * fraction, halfStrokeWidth, progressPaint);
      // Right (top to bottom).
      canvas.drawLine(width - halfStrokeWidth, 0, width - halfStrokeWidth, height * fraction,
          progressPaint);
      // Bottom (right to left).
      canvas.drawLine(width, height - halfStrokeWidth, width - (width * fraction),
          height - halfStrokeWidth, progressPaint);
      // Left (bottom to top).
      canvas.drawLine(halfStrokeWidth, height, halfStrokeWidth, height - (height * fraction),
          progressPaint);
    }

    if (doneFraction < 1) {
      // Top (left to right).
      canvas.drawLine(width * doneFraction, halfStrokeWidth, width, halfStrokeWidth, progressPaint);
      // Right (top to bottom).
      canvas.drawLine(width - halfStrokeWidth, height * doneFraction, width - halfStrokeWidth,
          height, progressPaint);
      // Bottom (right to left).
      canvas.drawLine(width - (width * doneFraction), height - halfStrokeWidth, 0,
          height - halfStrokeWidth, progressPaint);
      // Left (bottom to top).
      canvas.drawLine(halfStrokeWidth, height - (height * doneFraction), halfStrokeWidth, 0,
          progressPaint);
    }
  }

  private void start() {
    pressing = true;
    progressAnimator.setFloatValues(progressFraction, 1);
    progressAnimator.start();
    handler.postDelayed(trigger, TRIGGER_DURATION_MS);
  }

  private void stop() {
    pressing = false;
  }

  private void cancel() {
    stop();
    progressAnimator.cancel();
    progressCancelAnimator.setFloatValues(progressFraction, 0);
    progressCancelAnimator.start();
    handler.removeCallbacks(trigger);
  }

  private void trigger() {
    stop();

    if (vibrate && hasVibratePermission(getContext())) {
      vibrator.vibrate(VIBRATION_DURATION_MS);
    }

    switch (screenshotMode) {
      case SYSTEM:
        if (projectionManager != null && !screenshotChildrenOnly && screenshotTarget == this) {
          // Take a full screenshot of the device. Request permission first.
          registerRequestCaptureReceiver();
          getContext().startActivity(new Intent(getContext(), RequestCaptureActivity.class));
          break;
        }

        // System was requested but isn't supported. Fall through.
      case CANVAS:
        captureCanvasScreenshot();
        break;
      case NONE:
        new SaveScreenshotTask(null).execute();
        break;
      default:
        throw new IllegalStateException("Unknown screenshot mode: " + screenshotMode);
    }
  }

  private void captureCanvasScreenshot() {
    capturingStart();

    // Wait for the next frame to be sure our progress bars are hidden.
    post(new Runnable() {
      @Override public void run() {
        View view = getTargetView();
        view.setDrawingCacheEnabled(true);
        Bitmap screenshot = Bitmap.createBitmap(view.getDrawingCache());
        if (lens != null) {
          lens.onCapture(screenshot, new BitmapProcessorListener() {
            @Override public void onBitmapReady(Bitmap screenshot) {
              capturingEnd();
              new SaveScreenshotTask(screenshot).execute();
            }
          });
        }
        view.setDrawingCacheEnabled(false);
      }
    });
  }

  private void capturingStart() {
    progressAnimator.end();
    progressFraction = 0;

    capturing = true;
    invalidate();
  }

  private void capturingEnd() {
    capturing = false;
    doneAnimator.start();
  }

  /**
   * Unless {@code screenshotChildrenOnly} is true, navigate up the layout hierarchy until we find
   * the root view.
   */
  private View getTargetView() {
    View view = screenshotTarget;
    if (!screenshotChildrenOnly) {
      while (view.getRootView() != view) {
        view = view.getRootView();
      }
    }

    return view;
  }

  /** Recursive delete of a file or directory. */
  private static void delete(File file) {
    if (file.isDirectory()) {
      File[] files = file.listFiles();
      if (files != null) {
        for (File child : files) {
          delete(child);
        }
      }
    }

    file.delete();
  }

  private static File getScreenshotFolder(Context context) {
    return new File(context.getExternalFilesDir(null), "telescope");
  }

  private static boolean hasVibratePermission(Context context) {
    return context.checkPermission(VIBRATE, Process.myPid(), Process.myUid()) == PERMISSION_GRANTED;
  }

  /**
   * Save a screenshot to external storage, start the done animation, and call the capture
   * listener.
   */
  private class SaveScreenshotTask extends AsyncTask<Void, Void, File> {
    private final Bitmap screenshot;

    private SaveScreenshotTask(Bitmap screenshot) {
      this.screenshot = screenshot;
    }

    @Override protected void onPreExecute() {
      saving = true;
      invalidate();
    }

    @Override protected File doInBackground(Void... params) {
      if (screenshot == null) {
        return null;
      }

      try {
        screenshotFolder.mkdirs();

        File file = new File(screenshotFolder, SCREENSHOT_FILE_FORMAT.format(new Date()));
        FileOutputStream out = new FileOutputStream(file);

        screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.flush();
        out.close();

        return file;
      } catch (IOException e) {
        Log.e(TAG,
            "Failed to save screenshot. Is the WRITE_EXTERNAL_STORAGE permission requested?");
      }

      return null;
    }

    @Override protected void onPostExecute(File screenshot) {
      saving = false;

      if (lens != null) {
        lens.onCapture(screenshot);
      }
    }
  }

  private void registerRequestCaptureReceiver() {
    getContext().registerReceiver(requestCaptureReceiver, requestCaptureFilter);
  }

  private void unregisterRequestCaptureReceiver() {
    getContext().unregisterReceiver(requestCaptureReceiver);
  }

  @TargetApi(LOLLIPOP) private void captureNativeScreenshot(final MediaProjection projection) {
    capturingStart();

    // Wait for the next frame to be sure our progress bars are hidden.
    post(new Runnable() {
      @Override public void run() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);
        final int width = displayMetrics.widthPixels;
        final int height = displayMetrics.heightPixels;

        final ImageReader imageReader =
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1);
        Surface surface = imageReader.getSurface();

        final VirtualDisplay display =
            projection.createVirtualDisplay("telescope", width, height, displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, surface, null, null);

        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
          @Override public void onImageAvailable(ImageReader reader) {
            Image image = null;
            Bitmap bitmap = null;
            Bitmap croppedBitmap = null;
            FileOutputStream out = null;

            try {
              image = reader.acquireLatestImage();
              capturingEnd();

              if (image == null) {
                return;
              }

              saving = true;

              Image.Plane[] planes = image.getPlanes();
              ByteBuffer buffer = planes[0].getBuffer();
              int pixelStride = planes[0].getPixelStride();
              int rowStride = planes[0].getRowStride();
              int rowPadding = rowStride - pixelStride * width;

              bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height,
                  Bitmap.Config.ARGB_8888);
              bitmap.copyPixelsFromBuffer(buffer);

              // Trim the screenshot to the correct size.
              croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);

              screenshotFolder.mkdirs();

              File file = new File(screenshotFolder, SCREENSHOT_FILE_FORMAT.format(new Date()));
              out = new FileOutputStream(file);

              croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
              out.flush();

              saving = false;
              if (lens != null) {
                lens.onCapture(file);
              }
            } catch (IOException e) {
              Log.e(TAG,
                  "Failed to save screenshot. Is the WRITE_EXTERNAL_STORAGE permission requested?");
            } finally {
              imageReader.close();
              display.release();
              projection.stop();

              if (image != null) {
                image.close();
              }

              if (bitmap != null) {
                bitmap.recycle();
              }

              if (croppedBitmap != null) {
                croppedBitmap.recycle();
              }

              if (out != null) {
                try {
                  out.close();
                } catch (IOException ignored) {
                }
              }
            }
          }
        }, null);
      }
    });
  }
}
