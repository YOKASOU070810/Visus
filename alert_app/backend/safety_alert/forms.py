from django import forms
from django.contrib.auth.models import User
from django.contrib.auth.forms import UserCreationForm, AuthenticationForm
from .models import SafetyAlert, Profile


class UserRegistrationForm(UserCreationForm):
    """Form for user registration."""

    email = forms.EmailField(required=True,
                             widget=forms.EmailInput(attrs={'class': 'form-control', 'placeholder': 'Email'}))
    first_name = forms.CharField(max_length=30, required=True,
                                 widget=forms.TextInput(attrs={'class': 'form-control', 'placeholder': 'First Name'}))
    last_name = forms.CharField(max_length=30, required=True,
                                widget=forms.TextInput(attrs={'class': 'form-control', 'placeholder': 'Last Name'}))

    class Meta:
        model = User
        fields = ['first_name', 'last_name', 'email', 'password1', 'password2']
        widgets = {
            'password1': forms.PasswordInput(attrs={'class': 'form-control', 'placeholder': 'Create a password'}),
            'password2': forms.PasswordInput(attrs={'class': 'form-control', 'placeholder': 'Confirm password'}),
        }

    def save(self, commit=True):
        user = super().save(commit=False)
        user.username = self.cleaned_data['email']  # Use email as username
        user.first_name = self.cleaned_data['first_name'].capitalize()
        user.last_name = self.cleaned_data['last_name'].capitalize()

        if commit:
            user.save()

            # Use get_or_create to avoid creating duplicate profiles
            profile, created = Profile.objects.get_or_create(
                user=user,  # Ensure it's linked to the correct user
                defaults={
                    'first_name': user.first_name,
                    'last_name': user.last_name,
                }
            )

            if not created:
                # Update profile fields if the profile already exists
                profile.first_name = user.first_name
                profile.last_name = user.last_name
                profile.save()

        return user


class UserProfileForm(forms.ModelForm):
    """Form for editing user profile information and synchronizing with User model."""

    class Meta:
        model = Profile
        fields = ['first_name', 'last_name', 'profile_image']
        widgets = {
            'first_name': forms.TextInput(attrs={'class': 'form-control', 'placeholder': 'First Name'}),
            'last_name': forms.TextInput(attrs={'class': 'form-control', 'placeholder': 'Last Name'}),
            'profile_image': forms.ClearableFileInput(attrs={'class': 'form-control'}),
        }

    def __init__(self, *args, **kwargs):
        self.user = kwargs.pop('user', None)
        super(UserProfileForm, self).__init__(*args, **kwargs)
        if self.instance:  # Set initial values from the User model
            self.fields['first_name'].initial = self.instance.user.first_name
            self.fields['last_name'].initial = self.instance.user.last_name

    def save(self, commit=True):
        profile = super().save(commit=False)
        profile.first_name = self.cleaned_data['first_name'].capitalize()
        profile.last_name = self.cleaned_data['last_name'].capitalize()

        if commit:
            profile.save()

            # Update the User model's first name and last name as well
            user = profile.user
            user.first_name = profile.first_name
            user.last_name = profile.last_name
            user.save()

        return profile


class SafetyAlertForm(forms.ModelForm):
    """Form for creating or updating safety alerts."""

    class Meta:
        model = SafetyAlert
        fields = ['city', 'status']
        widgets = {
            'city': forms.TextInput(attrs={'class': 'form-control', 'placeholder': 'City'}),
            'status': forms.RadioSelect(choices=[(True, 'Safe'), (False, 'Not Safe')]),
        }


class UserAuthenticationForm(AuthenticationForm):
    """Custom authentication form that uses email as username."""

    username = forms.EmailField(label='Email', max_length=254,
                                widget=forms.EmailInput(attrs={'class': 'form-control', 'placeholder': 'Email'}))

    class Meta:
        model = User
        fields = ['username', 'password']


class UserSearchForm(forms.Form):
    """Form for searching users."""

    username = forms.CharField(max_length=100, required=False, label='Search by username',
                               widget=forms.TextInput(attrs={'class': 'form-control', 'placeholder': 'Enter username'}))
