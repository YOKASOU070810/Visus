package com.visus.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.visus.app.network.ApiClient;

public class SignupActivity extends Activity {

    private EditText firstNameInput, lastNameInput, emailInput, passwordInput, confirmPasswordInput;
    private Button signupButton;
    private TextView errorText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup);

        firstNameInput = findViewById(R.id.firstNameInput);
        lastNameInput = findViewById(R.id.lastNameInput);
        emailInput = findViewById(R.id.emailInput);
        passwordInput = findViewById(R.id.passwordInput);
        confirmPasswordInput = findViewById(R.id.confirmPasswordInput);
        signupButton = findViewById(R.id.signupButton);
        errorText = findViewById(R.id.errorText);

        signupButton.setOnClickListener(v -> doSignup());
        findViewById(R.id.loginLink).setOnClickListener(v -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    private void doSignup() {
        String firstName = firstNameInput.getText().toString().trim();
        String lastName = lastNameInput.getText().toString().trim();
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();
        String confirm = confirmPasswordInput.getText().toString().trim();

        if (TextUtils.isEmpty(firstName) || TextUtils.isEmpty(lastName)
                || TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            errorText.setText("Please fill in all fields");
            errorText.setVisibility(View.VISIBLE);
            return;
        }
        if (!password.equals(confirm)) {
            errorText.setText("Passwords do not match");
            errorText.setVisibility(View.VISIBLE);
            return;
        }

        errorText.setVisibility(View.GONE);
        signupButton.setEnabled(false);
        signupButton.setText("Creating account...");

        ApiClient.getInstance(this).signup(firstName, lastName, email, password, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                startActivity(new Intent(SignupActivity.this, HomeActivity.class));
                finish();
            }

            @Override
            public void onError(String message) {
                errorText.setText(message);
                errorText.setVisibility(View.VISIBLE);
                signupButton.setEnabled(true);
                signupButton.setText("Sign Up");
            }
        });
    }
}
