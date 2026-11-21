window.fetchCurrentUser = function() {
    return fetch('/api/me').then(function(response) {
        if (response.status === 401) {
            $('#auth-link').text('Login')
                .attr('href', '#login-dialog')
                .attr('data-auth-dialog', 'login-dialog');
            $('#login-link').hide();
            $('#my-tracks-link').attr('hidden', true);
            $('#auth-status').text('Sign in to use the protected API.');
        }
        return response;
    });
};
