package com.visus.app.core;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

public final class PermissionHelper {
    private static final int REQUEST_CODE = 1001;

    private PermissionHelper() {
    }

    public static void requestMissingPermissions(Activity activity, String[] permissions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        List<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {
            activity.requestPermissions(missingPermissions.toArray(new String[0]), REQUEST_CODE);
        }
    }
}

