from django.test import TestCase
from django.contrib.auth import get_user_model
from .models import SafetyAlert, Profile, Friendship

User = get_user_model()

class UserProfileModelTest(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(
            username='testuser',
            password='password123'
        )
        self.profile = Profile.objects.create(
            user=self.user,
            first_name='Test',
            last_name='User'
        )

    def test_profile_creation(self):
        self.assertEqual(self.profile.user.username, 'testuser')
        self.assertEqual(self.profile.first_name, 'Test')
        self.assertEqual(self.profile.last_name, 'User')

    def test_profile_str(self):
        self.assertEqual(str(self.profile), 'Test User')


class SafetyAlertModelTest(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(
            username='testuser',
            password='password123'
        )
        self.alert = SafetyAlert.objects.create(
            user=self.user,
            user_location='Location A',
            status=True
        )

    def test_alert_creation(self):
        self.assertEqual(self.alert.user.username, 'testuser')
        self.assertEqual(self.alert.user_location, 'Location A')
        self.assertTrue(self.alert.status)

    def test_alert_str(self):
        self.assertEqual(str(self.alert), f'Alert for {self.alert.user.username} at {self.alert.user_location}')


class FriendshipModelTest(TestCase):
    def setUp(self):
        self.user1 = User.objects.create_user(
            username='user1',
            password='password123'
        )
        self.user2 = User.objects.create_user(
            username='user2',
            password='password456'
        )
        self.friendship = Friendship.objects.create(
            user1=self.user1,
            user2=self.user2
        )

    def test_friendship_creation(self):
        self.assertEqual(self.friendship.user1.username, 'user1')
        self.assertEqual(self.friendship.user2.username, 'user2')

    def test_friendship_str(self):
        self.assertEqual(str(self.friendship), 'user1 - user2')


class SafetyAlertViewTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(
            username='testuser',
            password='password123'
        )
        self.client.login(username='testuser', password='password123')

    def test_create_safety_alert(self):
        response = self.client.post('/safety_alert/create/', {
            'user_location': 'Location B',
            'status': True
        })
        self.assertEqual(response.status_code, 302)  # Redirect after successful creation
        self.assertTrue(SafetyAlert.objects.filter(user=self.user, user_location='Location B').exists())

    def test_update_safety_alert(self):
        alert = SafetyAlert.objects.create(
            user=self.user,
            user_location='Location C',
            status=True
        )
        response = self.client.post(f'/safety_alert/update/{alert.id}/', {
            'user_location': 'Updated Location',
            'status': False
        })
        self.assertEqual(response.status_code, 302)
        alert.refresh_from_db()
        self.assertEqual(alert.user_location, 'Updated Location')
        self.assertFalse(alert.status)

    def test_delete_safety_alert(self):
        alert = SafetyAlert.objects.create(
            user=self.user,
            user_location='Location D',
            status=True
        )
        response = self.client.post(f'/safety_alert/delete/{alert.id}/')
        self.assertEqual(response.status_code, 302)
        self.assertFalse(SafetyAlert.objects.filter(id=alert.id).exists())


class FriendshipViewTests(TestCase):
    def setUp(self):
        self.user1 = User.objects.create_user(
            username='user1',
            password='password123'
        )
        self.user2 = User.objects.create_user(
            username='user2',
            password='password456'
        )
        self.friendship = Friendship.objects.create(
            user1=self.user1,
            user2=self.user2
        )
        self.client.login(username='user1', password='password123')

    def test_create_friendship(self):
        response = self.client.post('/friendship/create/', {
            'user1': self.user1.id,
            'user2': self.user2.id
        })
        self.assertEqual(response.status_code, 302)
        self.assertTrue(Friendship.objects.filter(user1=self.user1, user2=self.user2).exists())

    def test_delete_friendship(self):
        response = self.client.post(f'/friendship/delete/{self.friendship.id}/')
        self.assertEqual(response.status_code, 302)
        self.assertFalse(Friendship.objects.filter(id=self.friendship.id).exists())
