package com.visus.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private static final String PREFS_NAME = "alertbuddy_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "http://192.168.1.100:8000";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        EditText serverUrlInput = findViewById(R.id.serverUrlInput);
        Button saveButton = findViewById(R.id.saveButton);

        String currentUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
        serverUrlInput.setText(currentUrl);

        saveButton.setOnClickListener(v -> {
            String url = serverUrlInput.getText().toString().trim();
            if (TextUtils.isEmpty(url)) {
                url = DEFAULT_SERVER_URL;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.putString(KEY_SERVER_URL, url);
            editor.apply();
            Toast.makeText(this, "Server URL saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
