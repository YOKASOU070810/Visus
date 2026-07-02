package com.visus.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.visus.app.network.ApiClient;

import java.util.ArrayList;
import java.util.List;

public class SearchUsersActivity extends Activity {

    private EditText searchInput;
    private Button searchBtn;
    private ListView userList;
    private TextView emptyText;
    private ProgressBar progress;
    private ApiClient api;

    private final List<ApiClient.UserInfo> users = new ArrayList<>();
    private UserAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = ApiClient.getInstance(this);
        setContentView(R.layout.activity_search);

        searchInput = findViewById(R.id.searchInput);
        searchBtn = findViewById(R.id.searchBtn);
        userList = findViewById(R.id.userList);
        emptyText = findViewById(R.id.emptyText);
        progress = findViewById(R.id.progress);

        adapter = new UserAdapter();
        userList.setAdapter(adapter);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        searchBtn.setOnClickListener(v -> doSearch());
    }

    private void doSearch() {
        String query = searchInput.getText().toString().trim();
        if (TextUtils.isEmpty(query)) return;

        progress.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);

        api.searchUsers(query, new ApiClient.Callback<List<ApiClient.UserInfo>>() {
            @Override
            public void onSuccess(List<ApiClient.UserInfo> result) {
                progress.setVisibility(View.GONE);
                users.clear();
                users.addAll(result);
                adapter.notifyDataSetChanged();
                if (users.isEmpty()) emptyText.setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(String message) {
                progress.setVisibility(View.GONE);
                Toast.makeText(SearchUsersActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    class UserAdapter extends BaseAdapter {
        @Override public int getCount() { return users.size(); }
        @Override public Object getItem(int i) { return users.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_user_row, parent, false);
            ApiClient.UserInfo u = users.get(i);
            TextView name = v.findViewById(R.id.userName);
            TextView email = v.findViewById(R.id.userEmail);
            Button btn = v.findViewById(R.id.actionBtn);

            String displayName = !u.firstName.isEmpty() ? u.firstName + " " + u.lastName : u.username;
            name.setText(displayName);
            email.setText(u.username);

            if (u.isFriend) {
                btn.setText("Remove");
                btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFe63946));
                btn.setOnClickListener(click -> removeFriend(u, btn));
            } else if (u.requestPending) {
                btn.setText("Pending");
                btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF999999));
                btn.setEnabled(false);
            } else {
                btn.setText("Add Friend");
                btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1a73e8));
                btn.setEnabled(true);
                btn.setOnClickListener(click -> addFriend(u, btn));
            }
            return v;
        }
    }

    private void addFriend(ApiClient.UserInfo u, Button btn) {
        btn.setEnabled(false);
        btn.setText("Sending...");
        api.addFriend(u.id, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void r) {
                Toast.makeText(SearchUsersActivity.this, "Friend request sent!", Toast.LENGTH_SHORT).show();
                u.requestPending = true;
                adapter.notifyDataSetChanged();
            }
            @Override
            public void onError(String msg) {
                btn.setEnabled(true);
                btn.setText("Add Friend");
                Toast.makeText(SearchUsersActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void removeFriend(ApiClient.UserInfo u, Button btn) {
        btn.setEnabled(false);
        btn.setText("Removing...");
        api.removeFriend(u.id, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void r) {
                u.isFriend = false;
                u.requestPending = false;
                adapter.notifyDataSetChanged();
            }
            @Override
            public void onError(String msg) {
                btn.setEnabled(true);
                btn.setText("Remove");
                Toast.makeText(SearchUsersActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
