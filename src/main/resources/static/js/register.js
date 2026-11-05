(function () {
    var form = document.getElementById('registration-form');
    var message = document.getElementById('registration-message');
    var confirmPassword = form.elements.confirmPassword;
    var submitButton = form.querySelector('button[type="submit"]');
    var dialog = document.getElementById('register-dialog');
    var widgetId = null;
    var captchaLoading = false;
    var submitting = false;
    var aliasError = new URL(window.location.href).searchParams.get('aliasError');

    function showError(text) {
        message.textContent = text;
        message.className = 'form-message error';
    }

    function initializeCaptcha() {
        if (!dialog.open || captchaLoading || widgetId !== null) {
            return;
        }
        captchaLoading = true;
        fetch('/api/register/recaptcha')
            .then(function (response) {
                return response.json().then(function (body) {
                    if (!response.ok) {
                        throw new Error(body.error || 'Unable to load reCAPTCHA.');
                    }
                    return body.siteKey;
                });
            })
            .then(function (siteKey) {
                var script = document.createElement('script');
                script.src = 'https://www.google.com/recaptcha/api.js?render=explicit';
                script.async = true;
                script.defer = true;
                script.onerror = function () {
                    showError('Unable to load reCAPTCHA. Please reload the page and try again.');
                };
                script.onload = function () {
                    window.grecaptcha.ready(function () {
                        widgetId = window.grecaptcha.render('registration-recaptcha', {
                            sitekey: siteKey,
                            size: document.getElementById('registration-recaptcha').clientWidth < 304
                                ? 'compact' : 'normal',
                            callback: function () {
                                submitButton.disabled = submitting;
                            },
                            'expired-callback': function () {
                                submitButton.disabled = true;
                                showError('reCAPTCHA expired. Please complete the challenge again.');
                            },
                            'error-callback': function () {
                                submitButton.disabled = true;
                                showError('reCAPTCHA could not connect. Please try the challenge again.');
                            }
                        });
                    });
                };
                document.head.appendChild(script);
            })
            .catch(function (error) {
                showError(error.message);
                captchaLoading = false;
            });
    }

    new MutationObserver(initializeCaptcha).observe(dialog, {
        attributes: true,
        attributeFilter: ['open']
    });
    initializeCaptcha();

    if (aliasError === 'unavailable') {
        message.textContent = 'That alias is already in use.';
        message.className = 'form-message error';
    } else if (aliasError === 'invalid') {
        message.textContent = 'Your alias must be 3-30 characters and use only letters, numbers, or underscores.';
        message.className = 'form-message error';
    }

    function validatePasswordConfirmation() {
        var matches = form.password.value === confirmPassword.value;
        confirmPassword.setCustomValidity(matches ? '' : 'Passwords do not match.');
        return matches;
    }

    form.password.addEventListener('input', validatePasswordConfirmation);
    confirmPassword.addEventListener('input', validatePasswordConfirmation);

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        if (!validatePasswordConfirmation()) {
            confirmPassword.reportValidity();
            return;
        }
        if (submitting) {
            return;
        }
        var token = widgetId === null ? '' : window.grecaptcha.getResponse(widgetId);
        if (!token) {
            showError('Please complete the reCAPTCHA challenge.');
            return;
        }

        submitting = true;
        submitButton.disabled = true;
        message.textContent = 'Creating your account...';
        message.className = 'form-message';

        fetch('/api/register', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                userid: form.userid.value,
                email: form.email.value.trim(),
                password: form.password.value,
                recaptchaToken: token
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
                showError(error.message);
            })
            .finally(function () {
                submitting = false;
                window.grecaptcha.reset(widgetId);
                submitButton.disabled = true;
            });
    });
})();
