(function () {
    var form = document.getElementById('registration-form');
    var message = document.getElementById('registration-message');

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        message.textContent = 'Creating your account...';
        message.className = 'form-message';

        fetch('/api/register', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                userid: form.userid.value,
                email: form.email.value.trim(),
                password: form.password.value
            })
        })
            .then(function (response) {
                return response.json().then(function (body) {
                    if (!response.ok) {
                        throw new Error(body.error || 'Unable to create account.');
                    }
                    return body;
                });
            })
            .then(function (body) {
                window.location.replace('/index.html');
            })
            .catch(function (error) {
                message.textContent = error.message;
                message.className = 'form-message error';
            });
    });
})();
