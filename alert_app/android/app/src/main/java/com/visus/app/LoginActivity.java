package com.visus.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.visus.app.network.ApiClient;

public class LoginActivity extends Activity {

    private EditText emailInput, passwordInput;
    private Button loginButton;
    private TextView errorText, signupLink, serverUrlText;
    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        api = ApiClient.getInstance(this);
        if (api.isLoggedIn()) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_login);

        emailInput = findViewById(R.id.emailInput);
        passwordInput = findViewById(R.id.passwordInput);
        loginButton = findViewById(R.id.loginButton);
        errorText = findViewById(R.id.errorText);
        signupLink = findViewById(R.id.signupLink);
        serverUrlText = findViewById(R.id.serverUrlText);

        serverUrlText.setText(api.getBaseUrl());
        findViewById(R.id.changeServerBtn).setOnClickListener(v -> showServerDialog());

        loginButton.setOnClickListener(v -> doLogin());
        signupLink.setOnClickListener(v -> {
            startActivity(new Intent(this, SignupActivity.class));
            finish();
        });
    }

    private void showServerDialog() {
        EditText input = new EditText(this);
        input.setText(api.getBaseUrl());
        input.setPadding(24, 24, 24, 24);
        input.setTextSize(15);
        new AlertDialog.Builder(this)
                .setTitle("Server Address")
                .setMessage("Enter the AlertBuddy server URL.\nExample: http://192.168.1.5:8000")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(url)) {
                        if (!url.startsWith("http://") && !url.startsWith("https://"))
                            url = "http://" + url;
                        url = url.replaceAll("/+$", "");
                        api.setBaseUrl(url);
                        serverUrlText.setText(url);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doLogin() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            errorText.setText("Please fill in all fields");
            errorText.setVisibility(View.VISIBLE);
            return;
        }

        errorText.setVisibility(View.GONE);
        loginButton.setEnabled(false);
        loginButton.setText("Logging in...");

        api.login(email, password, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                finish();
            }

            @Override
            public void onError(String message) {
                errorText.setText(message);
                errorText.setVisibility(View.VISIBLE);
                loginButton.setEnabled(true);
                loginButton.setText("Log In");
            }
        });
    }
}
