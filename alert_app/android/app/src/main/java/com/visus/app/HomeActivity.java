package com.visus.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.visus.app.network.ApiClient;

import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends Activity {

    private ApiClient api;
    private Handler handler = new Handler(Looper.getMainLooper());

    // tabs
    private Button tabFriends, tabSearch, tabRequests, tabSettings;
    private View panelFriends, panelSearch, panelRequests, panelSettings;

    // friends panel
    private ListView friendsList;
    private Button btnSafe, btnUnsafe;
    private TextView statusMsg, emptyFriends;
    private ProgressBar progressFriends;
    private final List<ApiClient.FriendInfo> friends = new ArrayList<>();
    private FriendAdapter friendAdapter;

    // search panel
    private EditText searchInput;
    private Button searchBtn;
    private ListView searchResultsList;
    private TextView emptySearch;
    private ProgressBar progressSearch;
    private final List<ApiClient.UserInfo> searchUsers = new ArrayList<>();
    private SearchAdapter searchAdapter;

    // requests panel
    private ListView requestsList;
    private TextView emptyRequests;
    private ProgressBar progressRequests;
    private final List<ApiClient.FriendRequestInfo> requests = new ArrayList<>();
    private RequestAdapter requestAdapter;

    // location
    private LocationManager locationManager;
    private double lastLat, lastLng;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = ApiClient.getInstance(this);
        if (!api.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_home);

        // tabs
        tabFriends = findViewById(R.id.tabFriends);
        tabSearch = findViewById(R.id.tabSearch);
        tabRequests = findViewById(R.id.tabRequests);
        tabSettings = findViewById(R.id.tabSettings);
        panelFriends = findViewById(R.id.panelFriends);
        panelSearch = findViewById(R.id.panelSearch);
        panelRequests = findViewById(R.id.panelRequests);
        panelSettings = findViewById(R.id.panelSettings);

        tabFriends.setOnClickListener(v -> switchTab(0));
        tabSearch.setOnClickListener(v -> switchTab(1));
        tabRequests.setOnClickListener(v -> switchTab(2));
        tabSettings.setOnClickListener(v -> switchTab(3));

        // settings panel
        EditText serverInput = findViewById(R.id.settingsServerUrl);
        serverInput.setText(api.getBaseUrl());
        findViewById(R.id.settingsSaveBtn).setOnClickListener(v -> {
            String url = serverInput.getText().toString().trim();
            if (!TextUtils.isEmpty(url)) {
                if (!url.startsWith("http://") && !url.startsWith("https://"))
                    url = "http://" + url;
                url = url.replaceAll("/+$", "");
                api.setBaseUrl(url);
                serverInput.setText(url);
                TextView msg = findViewById(R.id.settingsMsg);
                msg.setText("Saved: " + url + "  (re-login required)");
                msg.setVisibility(View.VISIBLE);
            }
        });
        findViewById(R.id.settingsLogoutBtn).setOnClickListener(v -> {
            api.logout();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });

        // friends panel
        friendsList = findViewById(R.id.friendsList);
        btnSafe = findViewById(R.id.btnSafe);
        btnUnsafe = findViewById(R.id.btnUnsafe);
        statusMsg = findViewById(R.id.statusMsg);
        emptyFriends = findViewById(R.id.emptyFriends);
        progressFriends = findViewById(R.id.progressFriends);

        friendAdapter = new FriendAdapter();
        friendsList.setAdapter(friendAdapter);
        btnSafe.setOnClickListener(v -> updateStatus(true));
        btnUnsafe.setOnClickListener(v -> updateStatus(false));

        // search panel
        searchInput = findViewById(R.id.searchInput);
        searchBtn = findViewById(R.id.searchBtn);
        searchResultsList = findViewById(R.id.searchResultsList);
        emptySearch = findViewById(R.id.emptySearch);
        progressSearch = findViewById(R.id.progressSearch);

        searchAdapter = new SearchAdapter();
        searchResultsList.setAdapter(searchAdapter);
        searchBtn.setOnClickListener(v -> doSearch());

        // requests panel
        requestsList = findViewById(R.id.requestsList);
        emptyRequests = findViewById(R.id.emptyRequests);
        progressRequests = findViewById(R.id.progressRequests);

        requestAdapter = new RequestAdapter();
        requestsList.setAdapter(requestAdapter);

        initLocation();
        switchTab(0);
    }

    // ── tab switching ──

    private void switchTab(int idx) {
        int active = 0xFF1a73e8, inactive = 0xFF999999;
        tabFriends.setTextColor(idx == 0 ? active : inactive);
        tabSearch.setTextColor(idx == 1 ? active : inactive);
        tabRequests.setTextColor(idx == 2 ? active : inactive);
        tabSettings.setTextColor(idx == 3 ? active : inactive);
        tabFriends.setTextSize(idx == 0 ? 15 : 14);
        tabSearch.setTextSize(idx == 1 ? 15 : 14);
        tabRequests.setTextSize(idx == 2 ? 15 : 14);
        tabSettings.setTextSize(idx == 3 ? 15 : 14);

        panelFriends.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        panelSearch.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        panelRequests.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);

        if (idx == 0) loadFriends();
        if (idx == 1) searchInput.requestFocus();
        if (idx == 2) loadRequests();
        if (idx == 3) {
            EditText urlInput = findViewById(R.id.settingsServerUrl);
            urlInput.setText(api.getBaseUrl());
        }
    }

    // ── location ──

    private void initLocation() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 100);
            return;
        }
        startLocation();
    }

    @SuppressWarnings("MissingPermission")
    private void startLocation() {
        try {
            LocationListener listener = new LocationListener() {
                public void onLocationChanged(@NonNull Location loc) {
                    lastLat = loc.getLatitude(); lastLng = loc.getLongitude();
                }
            };
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5, listener, handler.getLooper());
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 10, listener, handler.getLooper());
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == 100 && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) startLocation();
    }

    // ── safety status ──

    private void updateStatus(boolean isSafe) {
        statusMsg.setText("Updating...");
        btnSafe.setEnabled(false); btnUnsafe.setEnabled(false);
        api.updateStatus(isSafe, lastLat, lastLng, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void r) {
                statusMsg.setText(isSafe ? "You are marked as SAFE" : "Alert sent! Friends notified.");
                btnSafe.setEnabled(true); btnUnsafe.setEnabled(true);
            }
            @Override
            public void onError(String msg) {
                statusMsg.setText("Failed: " + msg);
                btnSafe.setEnabled(true); btnUnsafe.setEnabled(true);
            }
        });
    }

    // ── friends list ──

    private void loadFriends() {
        progressFriends.setVisibility(View.VISIBLE);
        api.getFriends(new ApiClient.Callback<List<ApiClient.FriendInfo>>() {
            @Override
            public void onSuccess(List<ApiClient.FriendInfo> result) {
                progressFriends.setVisibility(View.GONE);
                friends.clear(); friends.addAll(result);
                friendAdapter.notifyDataSetChanged();
                emptyFriends.setVisibility(friends.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onError(String msg) {
                progressFriends.setVisibility(View.GONE);
                if ("auth".equals(msg)) { logout(); return; }
                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    class FriendAdapter extends BaseAdapter {
        @Override public int getCount() { return friends.size(); }
        @Override public Object getItem(int i) { return friends.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override
        public View getView(int i, View v, ViewGroup parent) {
            try {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_friend_card, parent, false);
                ApiClient.FriendInfo f = friends.get(i);

                String name = f.firstName != null && !f.firstName.isEmpty()
                        ? f.firstName + " " + (f.lastName != null ? f.lastName : "")
                        : (f.username != null ? f.username : "Unknown");
                ((TextView) v.findViewById(R.id.friendName)).setText(name);
                ((TextView) v.findViewById(R.id.friendEmail)).setText(f.username);

                View dot = v.findViewById(R.id.statusDot);
                TextView badge = v.findViewById(R.id.statusBadge);
                int badgeColor;
                if (f.isSafe == null) {
                    badgeColor = 0xFF999999;
                    badge.setText("No Status");
                } else if (f.isSafe) {
                    badgeColor = 0xFF34a853;
                    badge.setText("Safe");
                } else {
                    badgeColor = 0xFFe63946;
                    badge.setText("Not Safe!");
                }
                dot.setBackgroundColor(badgeColor);
                badge.setBackgroundColor(badgeColor);
                badge.setTextColor(0xFFFFFFFF);

                String loc = f.city != null ? f.city : "";
                String time = (f.lastUpdated != null && f.lastUpdated.length() >= 16)
                        ? "  " + f.lastUpdated.substring(11, 16) : "";
                ((TextView) v.findViewById(R.id.friendLocation)).setText(loc + time);
                return v;
            } catch (Exception e) {
                return v != null ? v : new View(HomeActivity.this);
            }
        }
    }

    // ── search ──

    private void doSearch() {
        String q = searchInput.getText().toString().trim();
        if (TextUtils.isEmpty(q)) return;
        progressSearch.setVisibility(View.VISIBLE);
        emptySearch.setVisibility(View.GONE);
        api.searchUsers(q, new ApiClient.Callback<List<ApiClient.UserInfo>>() {
            @Override
            public void onSuccess(List<ApiClient.UserInfo> result) {
                progressSearch.setVisibility(View.GONE);
                searchUsers.clear(); searchUsers.addAll(result);
                searchAdapter.notifyDataSetChanged();
                if (searchUsers.isEmpty()) emptySearch.setVisibility(View.VISIBLE);
            }
            @Override
            public void onError(String msg) {
                progressSearch.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    class SearchAdapter extends BaseAdapter {
        @Override public int getCount() { return searchUsers.size(); }
        @Override public Object getItem(int i) { return searchUsers.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override
        public View getView(int i, View v, ViewGroup parent) {
            try {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_user_row, parent, false);
                ApiClient.UserInfo u = searchUsers.get(i);
                String name = u.firstName != null && !u.firstName.isEmpty()
                        ? u.firstName + " " + (u.lastName != null ? u.lastName : "")
                        : (u.username != null ? u.username : "Unknown");
                ((TextView) v.findViewById(R.id.userName)).setText(name);
                ((TextView) v.findViewById(R.id.userEmail)).setText(u.username);
                Button btn = v.findViewById(R.id.actionBtn);
                if (u.isFriend) {
                    btn.setText("Remove"); btn.setEnabled(true);
                    btn.setBackgroundColor(0xFFe63946);
                    btn.setOnClickListener(click -> removeFriend(u, btn));
                } else if (u.requestPending) {
                    btn.setText("Pending"); btn.setEnabled(false);
                    btn.setBackgroundColor(0xFF999999);
                } else {
                    btn.setText("Add"); btn.setEnabled(true);
                    btn.setBackgroundColor(0xFF1a73e8);
                    btn.setOnClickListener(click -> addFriend(u, btn));
                }
                return v;
            } catch (Exception e) {
                return v != null ? v : new View(HomeActivity.this);
            }
        }
    }

    private void addFriend(ApiClient.UserInfo u, Button btn) {
        btn.setEnabled(false); btn.setText("...");
        api.addFriend(u.id, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void r) {
                Toast.makeText(HomeActivity.this, "Friend request sent!", Toast.LENGTH_SHORT).show();
                u.requestPending = true; searchAdapter.notifyDataSetChanged();
            }
            @Override public void onError(String msg) {
                btn.setEnabled(true); btn.setText("Add");
                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void removeFriend(ApiClient.UserInfo u, Button btn) {
        btn.setEnabled(false); btn.setText("...");
        api.removeFriend(u.id, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void r) {
                u.isFriend = false; u.requestPending = false;
                searchAdapter.notifyDataSetChanged(); loadFriends();
            }
            @Override public void onError(String msg) {
                btn.setEnabled(true); btn.setText("Remove");
                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ── requests ──

    private void loadRequests() {
        progressRequests.setVisibility(View.VISIBLE);
        api.getPendingRequests(new ApiClient.Callback<List<ApiClient.FriendRequestInfo>>() {
            @Override
            public void onSuccess(List<ApiClient.FriendRequestInfo> result) {
                progressRequests.setVisibility(View.GONE);
                requests.clear(); requests.addAll(result);
                requestAdapter.notifyDataSetChanged();
                emptyRequests.setVisibility(requests.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onError(String msg) {
                progressRequests.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    class RequestAdapter extends BaseAdapter {
        @Override public int getCount() { return requests.size(); }
        @Override public Object getItem(int i) { return requests.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_request_row, parent, false);
            ApiClient.FriendRequestInfo r = requests.get(i);
            ((TextView) v.findViewById(R.id.requestName))
                    .setText(r.firstName.isEmpty() ? r.username : r.firstName);
            v.findViewById(R.id.approveBtn).setOnClickListener(click -> respond(r, true));
            v.findViewById(R.id.declineBtn).setOnClickListener(click -> respond(r, false));
            return v;
        }
    }

    private void respond(ApiClient.FriendRequestInfo r, boolean approve) {
        api.respondRequest(r.id, approve, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(HomeActivity.this,
                        approve ? "Friend added!" : "Declined", Toast.LENGTH_SHORT).show();
                loadRequests(); loadFriends();
            }
            @Override
            public void onError(String msg) {
                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void logout() {
        api.logout();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
