# Emergency Alert System (危险播报功能)

Real-time safety alert system — send and receive location-aware alerts to friends/family.

## Architecture

```
Android App (原生)  ──HTTP/JSON──>  Django Server (局域网)
    WebView UI                         SQLite
    原生 GPS                           好友管理
    底部导航栏                          安全状态广播
```

## Backend Setup

```bash
cd backend
pip install -r requirements.txt
python manage.py migrate
python manage.py runserver 0.0.0.0:8000
```

## Demo Accounts

All passwords: `demo123`

| Account | Status |
|---------|--------|
| alice@demo.com | Safe @ Shanghai |
| bob@demo.com | Safe @ Beijing |
| carol@demo.com | Safe @ Shenzhen |
| dave@demo.com | NOT SAFE @ Guangzhou |
| eve@demo.com | Safe @ Hangzhou |
| frank@demo.com | Safe @ Chengdu |
| grace@demo.com | Safe @ Nanjing |
| henry@demo.com | Safe @ Wuhan |
| ivy@demo.com | NOT SAFE @ Suzhou |
| jack@demo.com | Safe @ Xi'an |
| newuser@demo.com | No friends (test account) |

## Android App

Open `android/` in Android Studio, build and install APK.

On first launch, go to Settings tab → enter the server's local IP (e.g. `http://172.20.10.3:8000`).

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | /api/login/ | Login (JSON: email, password) |
| POST | /api/signup/ | Register (form-data) |
| GET | /api/status/ | Get my safety status |
| POST | /api/status/update/ | Update status + GPS |
| GET | /api/friends/ | List friends with statuses |
| POST | /api/friends/add/ | Send friend request |
| POST | /api/friends/remove/ | Remove friend |
| GET | /api/friends/requests/ | Pending requests |
| POST | /api/friends/requests/<id>/approve/ | Approve request |
| POST | /api/friends/requests/<id>/decline/ | Decline request |
| POST | /api/search/ | Search users (JSON: query) |
