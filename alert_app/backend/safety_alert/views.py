from django.shortcuts import render, redirect, get_object_or_404
from django.contrib.auth.decorators import login_required
from django.http import JsonResponse
from django.contrib.auth import login, authenticate
from django.contrib.auth.models import User
from django.db.models import Q
import os
from .models import SafetyAlert, Friendship, FriendRequest
from .forms import UserSearchForm, UserRegistrationForm, UserAuthenticationForm, UserProfileForm


@login_required
def home(request):
    friendships = Friendship.objects.filter(Q(user1=request.user) | Q(user2=request.user))
    friends = {
        (friendship.user2 if friendship.user1 == request.user else friendship.user1): {
            'status': latest_alert.status if (latest_alert := SafetyAlert.objects.filter(user=friendship.user2 if friendship.user1 == request.user else friendship.user1).order_by('-last_updated').first()) else None,
            'last_alert_time': latest_alert.last_updated if latest_alert else None,
            'city': latest_alert.city if latest_alert else None,
            'full_name': f"{friendship.user1.first_name} {friendship.user1.last_name}"
        } for friendship in friendships
    }
    return render(request, 'home.html', {'friends': friends})


def signup(request):
    if request.method == 'POST':
        form = UserRegistrationForm(request.POST)
        if form.is_valid():
            user = form.save()
            login(request, user, backend='django.contrib.auth.backends.EmailBackend')
            return redirect('home')
    else:
        form = UserRegistrationForm()
    return render(request, 'signup.html', {'form': form})



def user_login(request):
    if request.method == 'POST':
        form = UserAuthenticationForm(data=request.POST)
        if form.is_valid():
            user = authenticate(request, username=form.cleaned_data.get('username'), password=form.cleaned_data.get('password'))
            if user is not None:
                login(request, user)
                return redirect('home')
    else:
        form = UserAuthenticationForm()
    return render(request, 'login.html', {'form': form})


@login_required
def update_safety_status(request, longitude=0, latitude=0):
    if request.method == 'POST':
        if request.POST.get('latitude', 0):
            latitude = request.POST.get('latitude', 0)
        if request.POST.get('longitude', 0):
            longitude = request.POST.get('longitude', 0)

        is_safe = request.POST.get('status') == 'true'
        location_data = {
            'city': request.POST.get('city'),
            'latitude': latitude,
            'longitude': longitude,
        }

        SafetyAlert.objects.create(user=request.user, status=is_safe, **location_data)
        return redirect('home')

    return JsonResponse({'success': False})


@login_required
def profile_view(request):
    latest_alert = SafetyAlert.objects.filter(user=request.user).order_by('-last_updated').first()
    return render(request, 'profile.html', {
        'user': request.user,
        'latest_alert': latest_alert,
        'status': ('Safe' if latest_alert.status == 'true' else 'Not Safe') if latest_alert else None,
        'city': latest_alert.city if latest_alert else None,
    })


@login_required
def edit_profile(request):
    # Get the current user's profile instance
    profile = request.user.profile
    old_image = profile.profile_image  # Store the old image

    # Initialize the form with the profile instance
    profile_form = UserProfileForm(instance=profile)

    if request.method == 'POST':
        profile_form = UserProfileForm(request.POST, request.FILES, instance=profile)

        if profile_form.is_valid():

            # Check if the profile image has changed
            if 'profile_image' in request.FILES and (request.FILES['profile_image'] != old_image):
                # Only delete the old image if a new one has been uploaded
                old_image.delete()
                profile.profile_image = request.FILES['profile_image']

            # Save the profile instance with the new image
            profile_form.save()
            return redirect('profile')  # Redirect to the profile or success page

    return render(request, 'edit_profile.html', {
        'profile_form': profile_form
    })


def search_users(request):
    search_term = request.POST.get('username', '')
    users = User.objects.filter(username__icontains=search_term).exclude(id=request.user.id)

    friends = Friendship.objects.filter(Q(user1=request.user) | Q(user2=request.user))
    friend_ids = [friend.user2.id if friend.user1 == request.user else friend.user1.id for friend in friends]

    pending_requests = FriendRequest.objects.filter(sender=request.user, is_pending=True)
    pending_user_ids = [req.receiver.id for req in pending_requests]

    context = {
        'form': UserSearchForm(),
        'users': users,
        'friend_ids': friend_ids,
        'pending_user_ids': pending_user_ids,
        'search_term': search_term
    }
    return render(request, 'search_users.html', context)


@login_required
def add_friend(request, user_id):
    friend = get_object_or_404(User, id=user_id)
    existing_request = FriendRequest.objects.filter(sender=request.user, receiver=friend, is_pending=True).exists()
    existing_friendship = Friendship.objects.filter(Q(user1=request.user, user2=friend) | Q(user1=friend, user2=request.user)).exists()

    if not existing_request and not existing_friendship:
        FriendRequest.objects.create(sender=request.user, receiver=friend)

    return redirect('search_users')


@login_required
def remove_friend(request, user_id):
    friend = get_object_or_404(User, id=user_id)
    friendships = Friendship.objects.filter(
        Q(user1=request.user, user2=friend) |
        Q(user1=friend, user2=request.user)
    )

    # Delete all friendships found
    friendships.delete()

    return redirect('search_users')


@login_required
def pending_friend_requests(request):
    pending_requests = FriendRequest.objects.filter(receiver=request.user, is_pending=True)
    return render(request, 'pending_requests.html', {'pending_requests': pending_requests})


@login_required
def approve_friend_request(request, request_id):
    friend_request = get_object_or_404(FriendRequest, id=request_id, receiver=request.user)

    if friend_request.receiver == request.user:
        Friendship.objects.create(user1=friend_request.receiver, user2=friend_request.sender)
        friend_request.accept()

    return redirect('pending_friend_requests')


@login_required
def decline_friend_request(request, request_id):
    friend_request = get_object_or_404(FriendRequest, id=request_id, receiver=request.user)
    friend_request.reject()

    return redirect('pending_friend_requests')
