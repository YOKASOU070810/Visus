# setup_demo.py - Create demo accounts for the merged Visus social system
"""Run this script once to populate the database with demo users."""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "src"))

from social.database import init_db, SessionLocal, User, SafetyAlert, Friendship

DEMO_USERS = [
    # Blind users
    ("alice@demo.com", "demo123", "Alice", "Wang", "blind"),
    ("bob@demo.com", "demo123", "Bob", "Li", "blind"),
    ("carol@demo.com", "demo123", "Carol", "Zhang", "blind"),
    ("eve@demo.com", "demo123", "Eve", "Chen", "blind"),
    ("grace@demo.com", "demo123", "Grace", "Zhao", "blind"),
    ("henry@demo.com", "demo123", "Henry", "Huang", "blind"),
    ("jack@demo.com", "demo123", "Jack", "Zhou", "blind"),
    # Family/caregiver users
    ("dave@demo.com", "demo123", "Dave", "Liu", "family"),
    ("frank@demo.com", "demo123", "Frank", "Yang", "family"),
    ("ivy@demo.com", "demo123", "Ivy", "Wu", "family"),
    # New test user (blind)
    ("newuser@demo.com", "demo123", "New", "User", "blind"),
]

def setup():
    init_db()
    db = SessionLocal()

    try:
        # Check if already set up
        existing = db.query(User).first()
        if existing:
            print("Demo data already exists. Skipping setup.")
            print(f"Existing users: {db.query(User).count()}")
            return

        users = []
        for email, password, first, last, utype in DEMO_USERS:
            user = User(
                username=email,
                email=email,
                first_name=first,
                last_name=last,
                user_type=utype,
            )
            user.set_password(password)
            db.add(user)
            db.flush()
            users.append(user)

            # Create initial safety status
            # Some users are "not safe" to demonstrate the feature
            is_safe = email not in ("dave@demo.com", "ivy@demo.com")
            city = {
                "alice@demo.com": "Shanghai",
                "bob@demo.com": "Beijing",
                "carol@demo.com": "Shenzhen",
                "dave@demo.com": "Guangzhou",
                "eve@demo.com": "Hangzhou",
                "frank@demo.com": "Chengdu",
                "grace@demo.com": "Nanjing",
                "henry@demo.com": "Wuhan",
                "ivy@demo.com": "Suzhou",
                "jack@demo.com": "Xi'an",
                "newuser@demo.com": "Shanghai",
            }.get(email, "")

            alert = SafetyAlert(
                user_id=user.id,
                status=is_safe,
                alert_type="manual",
                city=city,
            )
            db.add(alert)

        # Create friendships between all demo users (except newuser)
        demo_users = users[:-1]  # all except newuser
        for i, u1 in enumerate(demo_users):
            for u2 in demo_users[i+1:]:
                friendship = Friendship(user1_id=u1.id, user2_id=u2.id)
                db.add(friendship)

        db.commit()
        print(f"Demo setup complete!")
        print(f"  Created {len(users)} users (password: demo123)")
        print(f"  Created friendships between first 10 users")
        print(f"  newuser@demo.com has no friends (test account)")
        print(f"\nLogin with any email above, password: demo123")
        print(f"  e.g. alice@demo.com / demo123")

    except Exception as e:
        db.rollback()
        print(f"Setup failed: {e}")
        raise
    finally:
        db.close()

if __name__ == "__main__":
    setup()
