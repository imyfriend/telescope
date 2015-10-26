package com.mattprecious.telescope;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/** A transparent activity used to request permission for screen capturing. */
public final class RequestCaptureActivity extends Activity {
  public static final String RESULT_EXTRA_CODE = "code";
  public static final String RESULT_EXTRA_DATA = "data";

  private static final String TAG = "TelescopeCapture";
  private static final int REQUEST_CODE = 1;

  public static String getResultBroadcastAction(Context context) {
    return context.getPackageName() + ".telescope.CAPTURE";
  }

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      Log.e(TAG, "System capture activity started pre-lollipop.");
      finish();
      return;
    }

    requestCapture();
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP) private void requestCapture() {
    MediaProjectionManager projectionManager =
        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    Intent intent = projectionManager.createScreenCaptureIntent();
    startActivityForResult(intent, REQUEST_CODE);
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == REQUEST_CODE) {
      Intent intent = new Intent(getResultBroadcastAction(this));
      intent.putExtra(RESULT_EXTRA_CODE, resultCode);
      intent.putExtra(RESULT_EXTRA_DATA, data);
      sendBroadcast(intent);
      finish();
      return;
    }

    super.onActivityResult(requestCode, resultCode, data);
  }
}
