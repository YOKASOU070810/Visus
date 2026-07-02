"""Create 10 demo accounts with mutual friendships and sample statuses."""
import os, sys
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'SafetyProject.settings')
import django
django.setup()

from django.contrib.auth.models import User
from safety_alert.models import Friendship, SafetyAlert

users_data = [
    ('alice@demo.com', 'Alice', 'Wang', True, 'Shanghai'),
    ('bob@demo.com', 'Bob', 'Li', True, 'Beijing'),
    ('carol@demo.com', 'Carol', 'Zhang', True, 'Shenzhen'),
    ('dave@demo.com', 'Dave', 'Chen', False, 'Guangzhou'),
    ('eve@demo.com', 'Eve', 'Liu', True, 'Hangzhou'),
    ('frank@demo.com', 'Frank', 'Yang', True, 'Chengdu'),
    ('grace@demo.com', 'Grace', 'Zhao', True, 'Nanjing'),
    ('henry@demo.com', 'Henry', 'Huang', True, 'Wuhan'),
    ('ivy@demo.com', 'Ivy', 'Wu', False, 'Suzhou'),
    ('jack@demo.com', 'Jack', 'Zhou', True, "Xi'an"),
]

created = []
for email, first, last, is_safe, city in users_data:
    u, is_new = User.objects.get_or_create(username=email, defaults={
        'email': email, 'first_name': first, 'last_name': last})
    if is_new:
        u.set_password('demo123'); u.save()
    created.append(u)
    SafetyAlert.objects.create(user=u, status=is_safe, latitude=31.23, longitude=121.47, city=city)
    print(f"{'NEW' if is_new else 'EXISTS'}: {email} / demo123")

# Mutual friendships
for i, u1 in enumerate(created):
    for u2 in created[i+1:]:
        Friendship.objects.get_or_create(user1=u1, user2=u2)

# Blank test account
tu, _ = User.objects.get_or_create(username='newuser@demo.com', defaults={
    'email': 'newuser@demo.com', 'first_name': 'New', 'last_name': 'User'})
tu.set_password('demo123'); tu.save()

print(f"\nDone. {Friendship.objects.count()} friendships. Run with:")
print("  cd backend && python manage.py runserver 0.0.0.0:8000")
