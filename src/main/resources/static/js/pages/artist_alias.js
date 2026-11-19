window.initializeArtistPage = function() {
    if (window.cancelArtistPageRequests) {
        window.cancelArtistPageRequests();
    }
    var active = true;
    var requests = [];
    window.cancelArtistPageRequests = function() {
        active = false;
        requests.forEach(function(request) { request.abort(); });
    };

    var params = new URLSearchParams(window.location.search);
    var artistAlias = (params.get('artistAlias') || '').trim();
    var heading = document.getElementById('artist-alias-heading');
    heading.textContent = artistAlias || 'Artist not found';
    if (artistAlias) {
        document.title = 'Reason Station - ' + artistAlias;
    }

    initializeFeed('tracks', window.createTrackCard);
    initializeFeed('patches', window.createPatchCard);

    function initializeFeed(kind, createCard) {
        var prefix = 'artist-' + kind;
        var container = document.getElementById(prefix);
        var list = document.getElementById(prefix + '-list') || container;
        var loading = document.getElementById(prefix + '-loading');
        var message = document.getElementById(prefix + '-message');
        var pagination = document.getElementById(prefix + '-pagination');
        var previous = document.getElementById(prefix + '-previous-page');
        var next = document.getElementById(prefix + '-next-page');
        var status = document.getElementById(prefix + '-page-status');
        var page = 1;
        var currentRequest;

        list.replaceChildren();
        container.hidden = true;
        container.setAttribute('aria-busy', 'false');
        loading.hidden = true;
        pagination.hidden = true;
        previous.disabled = true;
        next.disabled = true;
        if (message) {
            message.hidden = true;
        }
        previous.onclick = function() { loadPage(page - 1); };
        next.onclick = function() { loadPage(page + 1); };
        if (artistAlias) {
            loadPage(1);
        }

        function loadPage(requestedPage) {
            if (!active || requestedPage < 1) {
                return;
            }
            if (currentRequest) {
                currentRequest.abort();
            }
            var request = new AbortController();
            currentRequest = request;
            requests.push(request);
            container.hidden = true;
            container.setAttribute('aria-busy', 'true');
            loading.hidden = false;
            previous.disabled = true;
            next.disabled = true;
            fetch('/api/discovery/artist-' + kind + '?artistAlias='
                + encodeURIComponent(artistAlias) + '&page=' + requestedPage, { signal: request.signal })
                .then(function(response) {
                    if (!response.ok) {
                        throw new Error('Unable to load artist ' + kind + '.');
                    }
                    return response.json();
                })
                .then(function(result) {
                    if (!active || currentRequest !== request) {
                        return;
                    }
                    page = result.page;
                    list.replaceChildren();
                    list.hidden = false;
                    if (message) {
                        message.hidden = true;
                    }
                    if (result.results.length === 0) {
                        list.appendChild(document.createElement('p')).textContent = 'No ' + kind + ' found.';
                    } else {
                        result.results.forEach(function(item) {
                            list.appendChild(createCard(item));
                        });
                    }
                    pagination.hidden = result.totalPages <= 1;
                    status.textContent = 'Page ' + page + ' of ' + result.totalPages;
                    previous.disabled = page <= 1;
                    next.disabled = page >= result.totalPages;
                    finishLoading();
                })
                .catch(function(error) {
                    if (!active || currentRequest !== request || error.name === 'AbortError') {
                        return;
                    }
                    list.replaceChildren();
                    pagination.hidden = true;
                    if (message) {
                        list.hidden = true;
                        message.textContent = 'Unable to load artist ' + kind + '.';
                        message.hidden = false;
                    } else {
                        list.appendChild(document.createElement('p')).textContent = 'Unable to load artist ' + kind + '.';
                    }
                    finishLoading();
                })
                .finally(function() {
                    var index = requests.indexOf(request);
                    if (index !== -1) {
                        requests.splice(index, 1);
                    }
                });
        }

        function finishLoading() {
            container.hidden = false;
            container.setAttribute('aria-busy', 'false');
            loading.hidden = true;
        }
    }
};
