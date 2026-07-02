from django.contrib import admin
from .models import SafetyAlert, Profile, Friendship

class SafetyAlertAdmin(admin.ModelAdmin):
    list_display = ('id', 'user', 'status', 'city', 'created_at')
    list_filter = ('status', 'created_at', 'user')
    search_fields = ('user__username', 'city')
    ordering = ('-created_at',)

    fieldsets = (
        (None, {
            'fields': ('user', 'city', 'status')
        }),
        ('Additional Information', {
            'fields': ('created_at', 'last_updated'),
            'classes': ('collapse',)
        }),
    )

    def get_readonly_fields(self, request, obj=None):
        if obj:
            return ['user']  # Make the user field read-only when editing
        return []

    # Optionally, you can add custom actions for bulk actions
    actions = ['mark_safe', 'mark_not_safe']

    def mark_safe(self, request, queryset):
        queryset.update(status=True)  # Assuming True means 'safe'
        self.message_user(request, "Selected alerts marked as safe.")

    def mark_not_safe(self, request, queryset):
        queryset.update(status=False)  # Assuming False means 'not safe'
        self.message_user(request, "Selected alerts marked as not safe.")

    mark_safe.short_description = "Mark selected alerts as safe"
    mark_not_safe.short_description = "Mark selected alerts as not safe"


class UserProfileAdmin(admin.ModelAdmin):
    list_display = ('user', 'get_first_name', 'get_last_name', 'profile_image', 'created_at')
    search_fields = ('user__username', 'first_name', 'last_name')
    ordering = ('created_at',)

    readonly_fields = ('created_at',)

    fieldsets = (
        (None, {
            'fields': ('user', 'first_name', 'last_name', 'profile_image')
        }),
        ('Timestamps', {
            'fields': ('created_at', 'last_updated'),
            'classes': ('collapse',)
        }),
    )

    def get_queryset(self, request):
        qs = super().get_queryset(request)
        return qs.select_related('user')  # Optimize queries

    # Add methods to fetch first_name and last_name from the user model
    def get_first_name(self, obj):
        return obj.first_name or obj.user.first_name

    def get_last_name(self, obj):
        return obj.last_name or obj.user.last_name

    get_first_name.short_description = 'First Name'
    get_last_name.short_description = 'Last Name'


class FriendshipAdmin(admin.ModelAdmin):
    list_display = ('user1', 'user2', 'created_at')
    list_filter = ('created_at',)
    search_fields = ('user1__username', 'user2__username')
    ordering = ('created_at',)

    fieldsets = (
        (None, {
            'fields': ('user1', 'user2')
        }),
        ('Timestamps', {
            'fields': ('created_at',)
        }),
    )

    # Optionally, add functionality to manage friendships
    actions = ['remove_friendship']

    def remove_friendship(self, request, queryset):
        for friendship in queryset:
            friendship.delete()
        self.message_user(request, "Selected friendships removed.")

    remove_friendship.short_description = "Remove selected friendships"


# Registering the models with the corresponding admin classes
admin.site.register(SafetyAlert, SafetyAlertAdmin)
admin.site.register(Profile, UserProfileAdmin)
admin.site.register(Friendship, FriendshipAdmin)
