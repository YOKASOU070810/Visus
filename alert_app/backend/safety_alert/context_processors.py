from .models import FriendRequest

def pending_requests_count(request):
    if request.user.is_authenticated:
        count = FriendRequest.objects.filter(receiver=request.user, is_pending=True).count()
        return {'pending_requests_count': count}
    return {'pending_requests_count': 0}
