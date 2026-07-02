package com.visus.app.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiClient {
    private static final String TAG = "ApiClient";
    private static final String PREFS_NAME = "alertbuddy_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_COOKIE = "session_cookie";
    public static final String DEFAULT_URL = "http://10.0.2.2:8000";

    private String baseUrl;
    private String sessionCookie;
    private final SharedPreferences prefs;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private static ApiClient instance;

    public static synchronized ApiClient getInstance(Context ctx) {
        if (instance == null) instance = new ApiClient(ctx.getApplicationContext());
        return instance;
    }

    public ApiClient(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        baseUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_URL);
        sessionCookie = prefs.getString(KEY_COOKIE, null);
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public String getBaseUrl() { return baseUrl; }

    public void setBaseUrl(String url) {
        baseUrl = url;
        sessionCookie = null;
        prefs.edit().putString(KEY_SERVER_URL, url).remove(KEY_COOKIE).apply();
    }

    private void saveSessionCookie(HttpURLConnection conn) {
        List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
        if (cookies != null) {
            for (String cookie : cookies) {
                if (cookie.startsWith("sessionid=")) {
                    sessionCookie = cookie.split(";")[0];
                    prefs.edit().putString(KEY_COOKIE, sessionCookie).apply();
                    break;
                }
            }
        }
    }

    private HttpURLConnection request(String path, String method, String jsonBody) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (sessionCookie != null) {
            conn.setRequestProperty("Cookie", sessionCookie);
        }
        if (jsonBody != null) {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.close();
        }
        return conn;
    }

    private JSONObject readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        saveSessionCookie(conn);
        if (code >= 200 && code < 300) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JSONObject(sb.toString());
        } else if (code == 401) {
            sessionCookie = null;
            prefs.edit().remove(KEY_COOKIE).apply();
            throw new AuthException("auth");
        } else {
            throw new Exception("HTTP " + code);
        }
    }

    // ── public API ──

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    public void login(String email, String password, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("password", password);
                HttpURLConnection conn = request("/api/login/", "POST", body.toString());
                int code = conn.getResponseCode();
                saveSessionCookie(conn);
                if (code >= 200 && code < 300) {
                    mainHandler.post(() -> callback.onSuccess(null));
                } else if (code == 401) {
                    sessionCookie = null;
                    prefs.edit().remove(KEY_COOKIE).apply();
                    mainHandler.post(() -> callback.onError("Invalid email or password"));
                } else {
                    mainHandler.post(() -> callback.onError("Server error (HTTP " + code + ")"));
                }
            } catch (java.net.ConnectException e) {
                Log.e(TAG, "login: cannot reach server at " + baseUrl, e);
                mainHandler.post(() -> callback.onError("Cannot connect to server at " + baseUrl));
            } catch (Exception e) {
                Log.e(TAG, "login error", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "Cannot connect to server"));
            }
        });
    }

    public void signup(String firstName, String lastName, String email, String password, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                URL url = new URL(baseUrl + "/api/signup/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                String formData = "first_name=" + java.net.URLEncoder.encode(firstName, "UTF-8") +
                        "&last_name=" + java.net.URLEncoder.encode(lastName, "UTF-8") +
                        "&email=" + java.net.URLEncoder.encode(email, "UTF-8") +
                        "&password1=" + java.net.URLEncoder.encode(password, "UTF-8") +
                        "&password2=" + java.net.URLEncoder.encode(password, "UTF-8");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = conn.getOutputStream();
                os.write(formData.getBytes("UTF-8"));
                os.close();
                int code = conn.getResponseCode();
                saveSessionCookie(conn);
                if (code == 201 || code == 200) {
                    mainHandler.post(() -> callback.onSuccess(null));
                } else {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    String errMsg = sb.toString();
                    Log.e(TAG, "signup failed: " + errMsg);
                    mainHandler.post(() -> callback.onError("Signup failed: " + errMsg));
                }
            } catch (java.net.ConnectException e) {
                mainHandler.post(() -> callback.onError("Cannot connect to " + baseUrl));
            } catch (Exception e) {
                Log.e(TAG, "signup error", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "Signup failed"));
            }
        });
    }

    public void updateStatus(boolean isSafe, double lat, double lng, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("status", isSafe);
                body.put("latitude", lat);
                body.put("longitude", lng);
                body.put("city", String.format("%.4f, %.4f", lat, lng));
                HttpURLConnection conn = request("/api/status/update/", "POST", body.toString());
                readResponse(conn);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Failed to update status"));
            }
        });
    }

    // ── data classes ──

    public static class FriendInfo {
        public int id;
        public String username;
        public String firstName;
        public String lastName;
        public Boolean isSafe;
        public String city;
        public String lastUpdated;
    }

    public static class UserInfo {
        public int id;
        public String username;
        public String email;
        public String firstName;
        public String lastName;
        public boolean isFriend;
        public boolean requestPending;
    }

    public static class FriendRequestInfo {
        public int id;
        public String username;
        public String firstName;
    }

    // ── friend APIs ──

    public void getFriends(Callback<List<FriendInfo>> callback) {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = request("/api/friends/", "GET", null);
                JSONObject resp = readResponse(conn);
                JSONArray arr = resp.getJSONObject("data").getJSONArray("friends");
                List<FriendInfo> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject f = arr.getJSONObject(i);
                    JSONObject user = f.getJSONObject("user");
                    FriendInfo fi = new FriendInfo();
                    fi.id = user.getInt("id");
                    fi.username = user.getString("username");
                    fi.firstName = user.optString("first_name", "");
                    fi.lastName = user.optString("last_name", "");
                    fi.isSafe = f.has("status") && !f.isNull("status") ? f.getBoolean("status") : null;
                    fi.city = f.optString("city", null);
                    fi.lastUpdated = f.optString("last_updated", null);
                    list.add(fi);
                }
                mainHandler.post(() -> callback.onSuccess(list));
            } catch (AuthException e) {
                mainHandler.post(() -> callback.onError("auth"));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Cannot load friends"));
            }
        });
    }

    public void searchUsers(String query, Callback<List<UserInfo>> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("query", query);
                HttpURLConnection conn = request("/api/search/", "POST", body.toString());
                JSONObject resp = readResponse(conn);
                JSONArray arr = resp.getJSONObject("data").getJSONArray("users");
                List<UserInfo> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject u = arr.getJSONObject(i);
                    JSONObject user = u.getJSONObject("user");
                    UserInfo ui = new UserInfo();
                    ui.id = user.getInt("id");
                    ui.username = user.getString("username");
                    ui.email = user.optString("email", "");
                    ui.firstName = user.optString("first_name", "");
                    ui.lastName = user.optString("last_name", "");
                    ui.isFriend = u.getBoolean("is_friend");
                    ui.requestPending = u.getBoolean("request_pending");
                    list.add(ui);
                }
                mainHandler.post(() -> callback.onSuccess(list));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Search failed"));
            }
        });
    }

    public void addFriend(int userId, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                HttpURLConnection conn = request("/api/friends/add/", "POST", body.toString());
                readResponse(conn);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Failed to add friend"));
            }
        });
    }

    public void removeFriend(int userId, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                HttpURLConnection conn = request("/api/friends/remove/", "POST", body.toString());
                readResponse(conn);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Failed to remove friend"));
            }
        });
    }

    public void getPendingRequests(Callback<List<FriendRequestInfo>> callback) {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = request("/api/friends/requests/", "GET", null);
                JSONObject resp = readResponse(conn);
                JSONArray arr = resp.getJSONObject("data").getJSONArray("requests");
                List<FriendRequestInfo> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    JSONObject sender = r.getJSONObject("sender");
                    FriendRequestInfo fri = new FriendRequestInfo();
                    fri.id = r.getInt("id");
                    fri.username = sender.getString("username");
                    fri.firstName = sender.optString("first_name", "");
                    list.add(fri);
                }
                mainHandler.post(() -> callback.onSuccess(list));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Failed to load requests"));
            }
        });
    }

    public void respondRequest(int requestId, boolean approve, Callback<Void> callback) {
        String action = approve ? "approve" : "decline";
        executor.execute(() -> {
            try {
                HttpURLConnection conn = request("/api/friends/requests/" + requestId + "/" + action + "/", "POST", "{}");
                readResponse(conn);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Failed"));
            }
        });
    }

    public boolean isLoggedIn() {
        return sessionCookie != null && !sessionCookie.isEmpty();
    }

    public void logout() {
        sessionCookie = null;
        prefs.edit().remove(KEY_COOKIE).apply();
    }

    public static class AuthException extends Exception {
        public AuthException(String msg) { super(msg); }
    }
}
