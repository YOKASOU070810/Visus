function selectSafetyStatus(isSafe) {
    document.getElementById('safety-status-value').value = isSafe;
    var btnSafe = document.getElementById('btn-safe');
    var btnUnsafe = document.getElementById('btn-unsafe');
    if (isSafe) {
        btnSafe.classList.add('safe-active');
        btnUnsafe.classList.remove('unsafe-active');
    } else {
        btnUnsafe.classList.add('unsafe-active');
        btnSafe.classList.remove('safe-active');
    }
}

function getLocation() {
    if (navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(
            function(position) {
                var lat = position.coords.latitude;
                var lng = position.coords.longitude;
                document.getElementById('latitude').value = lat;
                document.getElementById('longitude').value = lng;
                document.getElementById('city').value = lat.toFixed(4) + ', ' + lng.toFixed(4);

                fetch('https://nominatim.openstreetmap.org/reverse?lat=' + lat + '&lon=' + lng + '&format=json')
                    .then(function(response) { return response.json(); })
                    .then(function(data) {
                        var city = (data.address && (data.address.city || data.address.town || data.address.village))
                            || (lat.toFixed(4) + ', ' + lng.toFixed(4));
                        document.getElementById('city').value = city;
                    })
                    .catch(function() {
                        document.getElementById('city').value = lat.toFixed(4) + ', ' + lng.toFixed(4);
                    });
            },
            function(error) {
                switch(error.code) {
                    case error.PERMISSION_DENIED:
                        alert("Please allow location access for safety alerts.");
                        break;
                    default:
                        document.getElementById('city').value = 'Unknown';
                }
            },
            { enableHighAccuracy: true, timeout: 10000 }
        );
    } else {
        document.getElementById('city').value = 'Unknown';
    }
}

// auto-get location on page load
document.addEventListener('DOMContentLoaded', function() {
    if (document.getElementById('safety-status-form')) {
        getLocation();
    }
});

function filterUserList() {
    const searchInput = document.querySelector('input[name="username"]');
    const userList = document.getElementById('user-list');
    const userItems = userList.querySelectorAll('.user-item');

    searchInput.addEventListener('input', function () {
        const searchTerm = searchInput.value.toLowerCase();
        userItems.forEach(function (item) {
            const username = item.querySelector('.user-username').textContent.toLowerCase();
            if (username.includes(searchTerm)) {
                item.style.display = '';  // Show the item
            } else {
                item.style.display = 'none';  // Hide the item
            }
        });
    });
}

document.addEventListener('DOMContentLoaded', filterUserList);


// app.js

function initializeProfileImageUpload() {
    // Preview the selected image before upload
    document.getElementById('profile_image').addEventListener('change', function(e) {
        const file = e.target.files[0];
        if (file) {
            const reader = new FileReader();
            reader.onload = function(e) {
                document.getElementById('profile-image-preview').src = e.target.result; // Update the image source
            }
            reader.readAsDataURL(file);
        }
    });

    // Trigger file input when the upload button is clicked
    document.getElementById('upload-button').addEventListener('click', function() {
        document.getElementById('profile_image').click();
    });
}
