package com.visus.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.visus.app.network.ApiClient;

import java.util.ArrayList;
import java.util.List;

public class PendingRequestsActivity extends Activity {

    private ListView requestsList;
    private TextView emptyText;
    private ProgressBar progress;
    private ApiClient api;

    private final List<ApiClient.FriendRequestInfo> requests = new ArrayList<>();
    private RequestAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = ApiClient.getInstance(this);
        setContentView(R.layout.activity_requests);

        requestsList = findViewById(R.id.requestsList);
        emptyText = findViewById(R.id.emptyText);
        progress = findViewById(R.id.progress);

        adapter = new RequestAdapter();
        requestsList.setAdapter(adapter);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        loadRequests();
    }

    private void loadRequests() {
        progress.setVisibility(View.VISIBLE);
        api.getPendingRequests(new ApiClient.Callback<List<ApiClient.FriendRequestInfo>>() {
            @Override
            public void onSuccess(List<ApiClient.FriendRequestInfo> result) {
                progress.setVisibility(View.GONE);
                requests.clear();
                requests.addAll(result);
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(requests.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onError(String msg) {
                progress.setVisibility(View.GONE);
                Toast.makeText(PendingRequestsActivity.this, msg, Toast.LENGTH_SHORT).show();
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
            TextView name = v.findViewById(R.id.requestName);
            Button approve = v.findViewById(R.id.approveBtn);
            Button decline = v.findViewById(R.id.declineBtn);

            String displayName = !r.firstName.isEmpty() ? r.firstName : r.username;
            name.setText(displayName);

            approve.setOnClickListener(click -> respond(r, true));
            decline.setOnClickListener(click -> respond(r, false));
            return v;
        }
    }

    private void respond(ApiClient.FriendRequestInfo r, boolean approve) {
        api.respondRequest(r.id, approve, new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(PendingRequestsActivity.this,
                        approve ? "Friend added!" : "Request declined", Toast.LENGTH_SHORT).show();
                loadRequests();
            }
            @Override
            public void onError(String msg) {
                Toast.makeText(PendingRequestsActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
