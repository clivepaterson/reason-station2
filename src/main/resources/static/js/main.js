$(document).ready(function() {
    function getPageId(url) {
        return url.searchParams.get('view') === 'about'
            ? 'about-page'
            : url.searchParams.get('view') === 'links'
            ? 'links-page'
            : url.searchParams.get('view') === 'my-tracks'
            ? 'my-tracks-page'
            : url.searchParams.has('artistAlias') ? 'artist-page'
            : url.searchParams.has('search') ? 'search-page' : 'home-page';
    }

    function loadSearchPage(url) {
        var searchTerm = (url.searchParams.get('search') || '').trim();
        var searchResults = $('#search-results');
        $('#search-status').text(searchTerm ? 'Searching for "' + searchTerm + '"...' : 'Enter a search term.');
        if (!searchTerm) {
            searchResults.empty();
            return;
        }

        fetch('/api/discovery/search?query=' + encodeURIComponent(searchTerm))
            .then(function(response) {
                if (!response.ok) {
                    throw new Error('Unable to search tracks.');
                }
                return response.json();
            })
            .then(function(tracks) {
                searchResults.empty();
                $('#search-status').text(tracks.length + ' track' + (tracks.length === 1 ? '' : 's') + ' found.');
                if (tracks.length === 0) {
                    searchResults.append($('<p>').text('No tracks found.'));
                    return;
                }
                tracks.forEach(function(track) {
                    searchResults.append(window.createTrackCard(track, {
                        filenameFallback: 'By ' + track.artistAlias,
                        includeArtistInDownloadLabel: true,
                        showArtist: true
                    }));
                });
            })
            .catch(function(error) {
                $('#search-status').text(error.message);
                searchResults.empty();
            });
    }

    window.showPage = function(targetUrl, updateHistory) {
        var url = targetUrl instanceof URL ? targetUrl : new URL(targetUrl, window.location.origin);
        var pageId = getPageId(url);
        if (updateHistory) {
            window.history.pushState({}, '', url.pathname + url.search + url.hash);
        }

        $('#home-page, #my-tracks-page, #artist-page, #search-page, #links-page, #about-page').attr('hidden', true);
        $('#' + pageId).removeAttr('hidden');
        $('#track-search-input').val(url.searchParams.get('search') || '');
        document.title = pageId === 'home-page'
            ? 'Reason Station - Explore Ideas Through Reasoning'
            : pageId === 'artist-page'
                ? 'Reason Station - ' + (url.searchParams.get('artistAlias') || 'Artist')
                : 'Reason Station - ' + $('#' + pageId + ' h1').first().text();

        if (pageId === 'home-page' && window.loadDiscovery) {
            window.loadDiscovery();
        } else if (pageId === 'artist-page' && window.initializeArtistPage) {
            window.initializeArtistPage();
        } else if (pageId === 'my-tracks-page' && window.initializeMyTracks) {
            window.initializeMyTracks();
        } else if (pageId === 'search-page') {
            loadSearchPage(url);
        }
    };

    function navigateInternalLink(event) {
        if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) {
            return;
        }
        event.preventDefault();
        window.showPage(this.href, true);
    }

    $(document).on('click', '.logo-title, .artist-discovery-item a, .sidebar-artist-item a',
        navigateInternalLink);
    $('#my-tracks-link, #links-link, #about-link').on('click', navigateInternalLink);

    window.addEventListener('popstate', function() {
        window.showPage(window.location.href, false);
    });

    function openAuthDialog(dialogId) {
        var dialog = document.getElementById(dialogId);
        if (dialog && typeof dialog.showModal === 'function') {
            if (document.querySelector('.auth-dialog[open]')) {
                document.querySelector('.auth-dialog[open]').close();
            }
            dialog.showModal();
            var firstInput = dialog.querySelector('input');
            if (firstInput) {
                firstInput.focus();
            }
        }
    }

    $('[data-auth-dialog]').on('click', function(event) {
        var dialogId = $(this).attr('data-auth-dialog');
        if (dialogId) {
            event.preventDefault();
            openAuthDialog(dialogId);
        }
    });

    $('[data-dialog-close]').on('click', function() {
        $(this).closest('dialog')[0].close();
    });

    $('.auth-dialog').on('mousedown', function(event) {
        if (event.target === this) {
            this.close();
        }
    });

    var authQuery = new URL(window.location.href).searchParams;
    if (authQuery.get('login') === 'true' || authQuery.has('error')) {
        openAuthDialog('login-dialog');
    }

    var currentUrl = new URL(window.location.href);
    window.showPage(currentUrl, false);

    fetch('/api/me')
        .then(function(response) {
            if (!response.ok) {
                throw new Error('not authenticated');
            }
            return response.json();
        })
        .then(function(user) {
            $('#auth-link').text('Logout')
                .attr('href', '/logout')
                .removeAttr('data-auth-dialog');
            $('#login-link').hide();
            $('#my-tracks-link').removeAttr('hidden');
            $('#auth-status').text('Signed in as ' + user.username);
        })
        .catch(function(error) {
            var authStatus = $('#auth-status');
            if (authStatus.length) {
                authStatus.text(error.message === 'not authenticated'
                    ? 'Sign in to use the protected API.'
                    : error.message);
            }
        });

});
