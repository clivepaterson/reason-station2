(function() {
    var status = document.getElementById('users-status');
    var rows = document.getElementById('users-rows');
    var pagination = document.getElementById('users-pagination');
    var previous = document.getElementById('users-previous');
    var next = document.getElementById('users-next');
    var pageStatus = document.getElementById('users-page-status');
    var currentPage;
    var requestNumber = 0;

    async function loadUsers() {
        var request = ++requestNumber;
        var page = new URL(window.location.href).searchParams.get('page') || '1';
        status.textContent = 'Loading users...';
        rows.replaceChildren();
        pagination.hidden = true;
        try {
            var response = await fetch('/api/admin/users?page=' + encodeURIComponent(page), {
                headers: { Accept: 'application/json' },
                cache: 'no-store'
            });
            if (!response.ok) {
                throw new Error(response.status === 401 || response.status === 403
                    ? 'Administrator access is required. Please sign in with an admin account.'
                    : response.status === 400
                    ? 'Invalid page. Page numbers must be positive integers.'
                    : 'Unable to load users. Please reload the page to try again.');
            }
            var result = await response.json();
            if (request !== requestNumber) {
                return;
            }
            currentPage = result.page;
            result.items.forEach(function(user) {
                var row = document.createElement('tr');
                [user.userid, user.alias, user.email, user.emailVerified ? 'Yes' : 'No',
                    user.admin ? 'Yes' : 'No', new Date(user.dateCreated).toLocaleString()]
                    .forEach(function(value) {
                        var cell = document.createElement('td');
                        cell.textContent = value;
                        row.appendChild(cell);
                    });
                rows.appendChild(row);
            });
            status.textContent = result.totalElements === 0 ? 'No users found.'
                : result.items.length === 0 ? 'No users on this page.'
                : result.totalElements.toLocaleString() + ' users';
            pageStatus.textContent = 'Page ' + result.page + ' of ' + result.totalPages;
            previous.disabled = result.page <= 1;
            next.disabled = result.page >= result.totalPages;
            pagination.hidden = result.totalElements === 0;
        } catch (error) {
            if (request === requestNumber) {
                status.textContent = error.message;
            }
        }
    }

    function changePage(page) {
        var url = new URL(window.location.href);
        url.searchParams.set('page', page);
        window.history.pushState({}, '', url);
        loadUsers();
    }

    previous.addEventListener('click', function() { changePage(currentPage - 1); });
    next.addEventListener('click', function() { changePage(currentPage + 1); });
    window.addEventListener('popstate', loadUsers);
    loadUsers();
})();
