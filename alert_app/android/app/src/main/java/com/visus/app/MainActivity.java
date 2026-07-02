package com.visus.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.visus.app.network.ApiClient;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ApiClient api = ApiClient.getInstance(this);
        Intent intent = new Intent(this, api.isLoggedIn() ? HomeActivity.class : LoginActivity.class);
        startActivity(intent);
        finish();
    }
}
