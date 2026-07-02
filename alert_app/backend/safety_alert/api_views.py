import json
from django.contrib.auth import login, authenticate
from django.contrib.auth.models import User
from django.db.models import Q
from django.http import JsonResponse
from django.shortcuts import get_object_or_404
from django.views.decorators.csrf import csrf_exempt

from .forms import UserRegistrationForm
from .models import SafetyAlert, Friendship, FriendRequest


def api_response(success, data=None, error=None, status=200):
    result = {'success': success}
    if data is not None:
        result['data'] = data
    if error is not None:
        result['error'] = error
    return JsonResponse(result, status=status)


def user_json(user):
    return {
        'id': user.id,
        'username': user.username,
        'email': user.email,
        'first_name': user.first_name,
        'last_name': user.last_name,
    }


def alert_json(alert):
    return {
        'id': alert.id,
        'user_id': alert.user_id,
        'status': alert.status,
        'latitude': alert.latitude,
        'longitude': alert.longitude,
        'city': alert.city,
        'last_updated': alert.last_updated.isoformat() if alert.last_updated else None,
    }


# ── auth ──

@csrf_exempt
def api_login(request):
    if request.method != 'POST':
        return api_response(False, error='POST required', status=405)
    try:
        body = json.loads(request.body)
        email = body.get('email', '')
        password = body.get('password', '')
    except json.JSONDecodeError:
        return api_response(False, error='Invalid JSON', status=400)

    user = authenticate(request, username=email, password=password)
    if user:
        login(request, user)
        return api_response(True, data={'user': user_json(user)})
    return api_response(False, error='Invalid credentials', status=401)


@csrf_exempt
def api_signup(request):
    if request.method != 'POST':
        return api_response(False, error='POST required', status=405)
    form = UserRegistrationForm(request.POST)
    if form.is_valid():
        user = form.save()
        login(request, user, backend='django.contrib.auth.backends.ModelBackend')
        return api_response(True, data={'user': user_json(user)}, status=201)
    return api_response(False, error=form.errors, status=400)


# ── safety status ──

def api_status(request):
    if not request.user.is_authenticated:
        return api_response(False, error='Not authenticated', status=401)
    latest = SafetyAlert.objects.filter(user=request.user).order_by('-last_updated').first()
    return api_response(True, data={
        'my_status': alert_json(latest) if latest else None,
    })


@csrf_exempt
def api_status_update(request):
    if not request.user.is_authenticated:
        return api_response(False, error='Not authenticated', status=401)
    if request.method != 'POST':
        return api_response(False, error='POST required', status=405)
    try:
        body = json.loads(request.body)
    except json.JSONDecodeError:
        body = {}

    is_safe = body.get('status', True)
    lat = body.get('latitude', 0)
    lng = body.get('longitude', 0)
    city = body.get('city', '')

    alert = SafetyAlert.objects.create(
        user=request.user,
        status=is_safe,
        latitude=lat,
        longitude=lng,
        city=city,
    )
    return api_response(True, data={'alert': alert_json(alert)}, status=201)


# ── friends ──

def api_friends(request):
    if not request.user.is_authenticated:
        return api_response(False, error='Not authenticated', status=401)

    friendships = Friendship.objects.filter(Q(user1=request.user) | Q(user2=request.user))
    result = []
    for f in friendships:
        friend = f.user2 if f.user1 == request.user else f.user1
        latest = SafetyAlert.objects.filter(user=friend).order_by('-last_updated').first()
        result.append({
            'user': user_json(friend),
            'status': latest.status if latest else None,
            'latitude': latest.latitude if latest else None,
            'longitude': latest.longitude if latest else None,
            'city': latest.city if latest else None,
            'last_updated': latest.last_updated.isoformat() if latest and latest.last_updated else None,
        })
    return api_response(True, data={'friends': result})


@csrf_exempt
def api_friends_add(request):
    if not request.user.is_authenticated:
        return api_response(False, error='Not authenticated', status=401)
    if request.method != 'POST':
        return api_response(False, error='POST required', status=405)
    try:
        body = json.loads(request.body)
        user_id = body.get('user_id')
    except (json.JSONDecodeError, AttributeError):
        return api_response(False, error='Invalid JSON', status=400)

    friend = get_object_or_404(User, id=user_id)
    existing_request = FriendRequest.objects.filter(
        sender=request.user, receiver=friend, is_pending=True
    ).exists()
    existing_friendship = Friendship.objects.filter(
        Q(user1=request.user, user2=friend) | Q(user1=friend, user2=request.user)
    ).exists()

    if existing_request:
        return api_response(False, error='Friend request already sent')
    if existing_friendship:
        return api_response(False, error='Already friends')

    FriendRequest.objects.create(sender=request.user, receiver=friend)
    return api_response(True, data={'message': 'Friend request sent'})


@csrf_exempt
def api_friends_remove(request):
    if not request.user.is_authenticated:
        return api_response(False, error='Not authenticated', status=401)
    if request.method != 'POST':
        return api_response(False, error='POST required', status=405)
    try:
        body = json.loads(request.body)
        user_id = body.get('user_id')
    except (json.JSONDecodeError, AttributeError):
        return api_response(False, error='Invalid JSON', status=400)

    friend = get_object_or_404(User, id=user_id)
    Friendship.objects.filter(
        Q(user1=request.user, user2=friend) | Q(user1=friend, user2=request.user)
    ).delete()
    return api_response(True, data={'message': 'Friend removed'})


# ── friend requests ──

def api_friend_requests(request):
    if not request.user.is_authenticated:
        return api_response(False, error='Not authenticated', status=401)

    pending = FriendRequest.objects.filter(receiver=request.user, is_pending=True)
    result = [{
        'id': r.id,
        'sender': user_json(r.sender),
        'created_at': r.created_at.isoformat(),
    } for r in pending]
    return api_response(True, data={'requests': result})


@csrf_exempt
def api_friend_request_respond(request, request_id, action):
    if not request.user.is_authenticated:
        return api_response(False, error='Not authenticated', status=401)
    if request.method != 'POST':
        return api_response(False, error='POST required', status=405)

    fr = get_object_or_404(FriendRequest, id=request_id, receiver=request.user)
    if action == 'approve':
        fr.accept()
        return api_response(True, data={'message': 'Friend request approved'})
    elif action == 'decline':
        fr.reject()
        return api_response(True, data={'message': 'Friend request declined'})
    return api_response(False, error='Invalid action', status=400)


# ── search ──

@csrf_exempt
def api_search_users(request):
    if not request.user.is_authenticated:
        return api_response(False, error='Not authenticated', status=401)
    if request.method != 'POST':
        return api_response(False, error='POST required', status=405)

    try:
        body = json.loads(request.body)
        query = body.get('query', '')
    except (json.JSONDecodeError, AttributeError):
        query = ''

    users = User.objects.filter(username__icontains=query).exclude(id=request.user.id)[:20]
    friendships = Friendship.objects.filter(Q(user1=request.user) | Q(user2=request.user))
    friend_ids = set()
    for f in friendships:
        friend_ids.add(f.user2_id if f.user1_id == request.user.id else f.user1_id)

    pending_sent = set(
        FriendRequest.objects.filter(sender=request.user, is_pending=True)
        .values_list('receiver_id', flat=True)
    )

    result = []
    for u in users:
        result.append({
            'user': user_json(u),
            'is_friend': u.id in friend_ids,
            'request_pending': u.id in pending_sent,
        })
    return api_response(True, data={'users': result})
