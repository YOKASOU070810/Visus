from django.db import models
from django.contrib.auth.models import User
from django.db.models.signals import post_save
from django.dispatch import receiver
from django.utils import timezone
from django.core.exceptions import ValidationError

class SafetyAlert(models.Model):
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='safety_alerts')
    status = models.BooleanField(default=False)  # Indicates if the user is safe
    latitude = models.FloatField(blank=True, null=True, default=0)
    longitude = models.FloatField(blank=True, null=True, default=0)
    city = models.CharField(max_length=100, blank=True, null=True)  # City name
    last_updated = models.DateTimeField(auto_now=True)  # Automatically updates to current time on save
    created_at = models.DateTimeField(default=timezone.now)

    def __str__(self):
        return f"{self.user.username} - {'Safe' if self.status else 'Not Safe'}"


# Model to manage friend requests
class FriendRequest(models.Model):
    sender = models.ForeignKey(User, related_name='sent_requests', on_delete=models.CASCADE)
    receiver = models.ForeignKey(User, related_name='received_requests', on_delete=models.CASCADE)
    is_pending = models.BooleanField(default=True)  # Indicates if the request is still pending
    created_at = models.DateTimeField(default=timezone.now)  # Timestamp when request was created

    def accept(self):
        self.is_pending = False
        self.save()
        Friendship.objects.create(user1=self.sender, user2=self.receiver)

    def reject(self):
        self.delete()  # Deletes the request upon rejection

    def __str__(self):
        return f"{self.sender.username} -> {self.receiver.username}"


# Model to represent friendships
class Friendship(models.Model):
    user1 = models.ForeignKey(User, related_name='friendships1', on_delete=models.CASCADE)
    user2 = models.ForeignKey(User, related_name='friendships2', on_delete=models.CASCADE)
    created_at = models.DateTimeField(default=timezone.now)
    class Meta:
        unique_together = ('user1', 'user2')  # Ensure that each friendship is unique

    def __str__(self):
        return f"{self.user1.username} & {self.user2.username}"


# User Profile Model to store additional user information
class Profile(models.Model):
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='profile')
    first_name = models.CharField(max_length=30, blank=True, null=True)
    last_name = models.CharField(max_length=30, blank=True, null=True)
    profile_image = models.ImageField(upload_to='profile_images/', blank=True, null=True)  # Profile image field
    created_at = models.DateTimeField(default=timezone.now)
    last_updated = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"Profile of {self.user.username}"


# Signal to create or save Profile automatically when User is created or updated
@receiver(post_save, sender=User)
def create_or_save_profile(sender, instance, created, **kwargs):
    if created:
        Profile.objects.create(user=instance)
    else:
        instance.profile.save()
