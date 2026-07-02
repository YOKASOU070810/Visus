from django.urls import path
from . import api_views

urlpatterns = [
    path('login/', api_views.api_login, name='api_login'),
    path('signup/', api_views.api_signup, name='api_signup'),
    path('status/', api_views.api_status, name='api_status'),
    path('status/update/', api_views.api_status_update, name='api_status_update'),
    path('friends/', api_views.api_friends, name='api_friends'),
    path('friends/add/', api_views.api_friends_add, name='api_friends_add'),
    path('friends/remove/', api_views.api_friends_remove, name='api_friends_remove'),
    path('friends/requests/', api_views.api_friend_requests, name='api_friend_requests'),
    path('friends/requests/<int:request_id>/<str:action>/', api_views.api_friend_request_respond, name='api_friend_request_respond'),
    path('search/', api_views.api_search_users, name='api_search_users'),
]
