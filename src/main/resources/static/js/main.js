$(document).ready(async function() {
    var pageFragments = [
        'home.html',
        'my-tracks.html',
        'artist.html',
        'search.html',
        'links.html',
        'tutorials.html',
        'tutorials/tutorial-kick-drum.html',
        'tutorials/tutorial-hi-hat.html',
        'tutorials/tutorial-open-hi-hat.html',
        'tutorials/tutorial-snare-drum.html',
        'tutorials/tutorial-bass.html',
        'tutorials/tutorial-pwm-lead.html',
        'about.html'
    ];

    try {
        var pages = await Promise.all(pageFragments.map(function(filename) {
            return fetch('/pages/' + filename).then(function(response) {
                if (!response.ok) {
                    throw new Error('Unable to load page: ' + filename);
                }
                return response.text();
            });
        }));
        $('#site-content').html(pages.join('\n'));
    } catch (error) {
        $('#site-content')
            .empty()
            .append($('<p>').addClass('form-message error').text(error.message));
        return;
    }

    var defaultMetadata = {
        title: 'Reason Station | Share Reason Music & Sound Synthesis',
        description: 'Discover, share, and download Reason music projects while learning sound synthesis techniques from other artists.'
    };

    function getPageId(url) {
        return url.searchParams.get('tutorial') === 'creating-a-pwm-lead'
            ? 'tutorial-pwm-lead-page'
            : url.searchParams.get('tutorial') === 'creating-a-bass'
            ? 'tutorial-bass-page'
            : url.searchParams.get('tutorial') === 'creating-a-snare-drum'
            ? 'tutorial-snare-page'
            : url.searchParams.get('tutorial') === 'creating-an-open-hi-hat'
            ? 'tutorial-open-hi-hat-page'
            : url.searchParams.get('tutorial') === 'creating-a-hi-hat'
            ? 'tutorial-hi-hat-page'
            : url.searchParams.get('tutorial') === 'creating-a-kick-drum'
            ? 'tutorial-detail-page'
            : url.searchParams.get('view') === 'tutorials'
            ? 'tutorials-page'
            : url.searchParams.get('view') === 'about'
            ? 'about-page'
            : url.searchParams.get('view') === 'links'
            ? 'links-page'
            : url.searchParams.get('view') === 'my-tracks'
            ? 'my-tracks-page'
            : url.searchParams.has('artistAlias') ? 'artist-page'
            : url.searchParams.has('search') ? 'search-page' : 'home-page';
    }

    function setMetaContent(selector, content) {
        $(selector).attr('content', content);
    }

    function updatePageMetadata(pageId, url) {
        var artistAlias = (url.searchParams.get('artistAlias') || '').trim();
        var pageMetadata = {
            'home-page': defaultMetadata,
            'artist-page': {
                title: (artistAlias || 'Artist') + ' | Reason Station',
                description: artistAlias
                    ? 'Explore Reason music projects and tracks shared by ' + artistAlias + ' on Reason Station.'
                    : 'Explore artist tracks and Reason music projects shared on Reason Station.'
            },
            'links-page': {
                title: 'Reason Resources & Tutorials | Reason Station',
                description: 'Explore selected Reason Studios resources, sound synthesis guides, and music production tutorials.'
            },
            'tutorials-page': {
                title: 'Beginner Synthesis Tutorials | Reason Station',
                description: 'Learn synthesis from the ground up with beginner tutorials using Reason Subtractor and effects.'
            },
            'tutorial-detail-page': {
                title: 'Creating a Kick Drum with Subtractor | Reason Station',
                description: 'Learn how to create a kick drum using Reason Subtractor.'
            },
            'tutorial-hi-hat-page': {
                title: 'Creating a Hi-Hat with Subtractor | Reason Station',
                description: 'Learn how to create a hi-hat using the Noise Generator and high-pass filter in Reason Subtractor.'
            },
            'tutorial-open-hi-hat-page': {
                title: 'Creating an Open Hi-Hat with Subtractor | Reason Station',
                description: 'Learn how to create an open hi-hat with a longer noise decay and distortion in Reason Subtractor.'
            },
            'tutorial-snare-page': {
                title: 'Creating a Snare Drum with Subtractor | Reason Station',
                description: 'Learn how to create a snare drum using two Reason Subtractors, a Line Mixer, compression, and distortion.'
            },
            'tutorial-bass-page': {
                title: 'Creating a Bass with Subtractor | Reason Station',
                description: 'Learn how to create a bass sound using sawtooth oscillators, a low-pass filter, and sidechain compression in Reason Subtractor.'
            },
            'tutorial-pwm-lead-page': {
                title: 'Creating a PWM Lead or Pad with Subtractor | Reason Station',
                description: 'Learn how to create a rich lead or pad using pulse width modulation, effects, and a chord progression in Reason Subtractor.'
            },
            'about-page': {
                title: 'About Reason Station',
                description: 'Learn why Reason Station was created as a community for sharing Reason projects and sound synthesis techniques.'
            },
            'search-page': {
                title: 'Search Tracks | Reason Station',
                description: 'Search for artists and Reason music projects shared by the Reason Station community.'
            },
            'my-tracks-page': {
                title: 'My Tracks | Reason Station',
                description: 'Manage your Reason Station tracks and project uploads.'
            }
        }[pageId] || defaultMetadata;
        var shouldIndex = pageId !== 'search-page' && pageId !== 'my-tracks-page';
        var canonicalUrl = new URL('/', window.location.origin);

        if (pageId === 'artist-page' && artistAlias) {
            canonicalUrl.searchParams.set('artistAlias', artistAlias);
        } else if (pageId === 'tutorial-pwm-lead-page') {
            canonicalUrl.searchParams.set('tutorial', 'creating-a-pwm-lead');
        } else if (pageId === 'tutorial-bass-page') {
            canonicalUrl.searchParams.set('tutorial', 'creating-a-bass');
        } else if (pageId === 'tutorial-snare-page') {
            canonicalUrl.searchParams.set('tutorial', 'creating-a-snare-drum');
        } else if (pageId === 'tutorial-open-hi-hat-page') {
            canonicalUrl.searchParams.set('tutorial', 'creating-an-open-hi-hat');
        } else if (pageId === 'tutorial-hi-hat-page') {
            canonicalUrl.searchParams.set('tutorial', 'creating-a-hi-hat');
        } else if (pageId === 'tutorial-detail-page') {
            canonicalUrl.searchParams.set('tutorial', 'creating-a-kick-drum');
        } else if (pageId === 'tutorials-page') {
            canonicalUrl.searchParams.set('view', 'tutorials');
        } else if (pageId === 'links-page') {
            canonicalUrl.searchParams.set('view', 'links');
        } else if (pageId === 'about-page') {
            canonicalUrl.searchParams.set('view', 'about');
        }

        document.title = pageMetadata.title;
        $('link[rel="canonical"]').attr('href', canonicalUrl.href);
        setMetaContent('meta[name="description"]', pageMetadata.description);
        setMetaContent('meta[name="robots"]', shouldIndex
            ? 'index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1'
            : 'noindex, follow');
        setMetaContent('meta[property="og:title"]', pageMetadata.title);
        setMetaContent('meta[property="og:description"]', pageMetadata.description);
        setMetaContent('meta[property="og:url"]', canonicalUrl.href);
        setMetaContent('meta[property="og:image"]',
            new URL('/images/logo_by_chatgpt.jpg', window.location.origin).href);
        setMetaContent('meta[name="twitter:title"]', pageMetadata.title);
        setMetaContent('meta[name="twitter:description"]', pageMetadata.description);
        setMetaContent('meta[name="twitter:image"]',
            new URL('/images/logo_by_chatgpt.jpg', window.location.origin).href);
    }

    function addWebsiteStructuredData() {
        $('#website-structured-data').text(JSON.stringify({
            '@context': 'https://schema.org',
            '@type': 'WebSite',
            name: 'Reason Station',
            url: new URL('/', window.location.origin).href,
            description: defaultMetadata.description,
            potentialAction: {
                '@type': 'SearchAction',
                target: {
                    '@type': 'EntryPoint',
                    urlTemplate: new URL('/?search={search_term_string}', window.location.origin).href
                },
                'query-input': 'required name=search_term_string'
            }
        }));
    }

    function loadSearchPage(url) {
        var searchTerm = (url.searchParams.get('search') || '').trim();
        var requestedPage = Number.parseInt(url.searchParams.get('page') || '1', 10);
        var page = Number.isInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1;
        var searchResults = $('#search-results');
        var searchPagination = $('#search-pagination');
        $('#search-status').text(searchTerm ? 'Searching for "' + searchTerm + '"...' : 'Enter a search term.');
        if (!searchTerm) {
            searchResults.empty();
            searchPagination.attr('hidden', true);
            return;
        }

        fetch('/api/discovery/search?query=' + encodeURIComponent(searchTerm) + '&page=' + page)
            .then(function(response) {
                return response.json().then(function(body) {
                    if (!response.ok) {
                        throw new Error(body.error || 'Unable to search tracks.');
                    }
                    return body;
                });
            })
            .then(function(searchPage) {
                var tracks = searchPage.results;
                searchResults.empty();
                $('#search-status').text(searchPage.totalElements + ' track'
                    + (searchPage.totalElements === 1 ? '' : 's') + ' found.');
                searchPagination.attr('hidden', searchPage.totalPages <= 1);
                $('#search-page-status').text('Page ' + searchPage.page + ' of ' + searchPage.totalPages);
                $('#search-previous-page')
                    .prop('disabled', searchPage.page <= 1)
                    .data('page', searchPage.page - 1);
                $('#search-next-page')
                    .prop('disabled', searchPage.page >= searchPage.totalPages)
                    .data('page', searchPage.page + 1);
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
                searchPagination.attr('hidden', true);
            });
    }

    function navigateToSearchPage(page) {
        var url = new URL(window.location.href);
        if (page === 1) {
            url.searchParams.delete('page');
        } else {
            url.searchParams.set('page', page);
        }
        window.showPage(url, true);
    }

    function updateSearchButtonState() {
        var searchExpression = $('#track-search-input').val() || '';
        var alphanumericCharacters = searchExpression.match(/[\p{L}\p{N}]/gu) || [];
        $('#track-search-form button[type="submit"]').prop('disabled', alphanumericCharacters.length < 2);
    }

    window.showPage = function(targetUrl, updateHistory) {
        var url = targetUrl instanceof URL ? targetUrl : new URL(targetUrl, window.location.origin);
        var pageId = getPageId(url);
        if (updateHistory) {
            window.history.pushState({}, '', url.pathname + url.search + url.hash);
        }

        $('#home-page, #my-tracks-page, #artist-page, #search-page, #links-page, #tutorials-page, #tutorial-detail-page, #tutorial-hi-hat-page, #tutorial-open-hi-hat-page, #tutorial-snare-page, #tutorial-bass-page, #tutorial-pwm-lead-page, #about-page').attr('hidden', true);
        $('#' + pageId).removeAttr('hidden');
        updateSearchButtonState();
        updatePageMetadata(pageId, url);

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

    $(document).on('click', '.logo-title, .artist-discovery-item a, .sidebar-artist-item a, .tutorial-card, .tutorial-back-link',
        navigateInternalLink);
    $('#my-tracks-link, #tutorials-link, #links-link, #about-link').on('click', navigateInternalLink);
    $('#track-search-input').on('input', updateSearchButtonState);
    $('#search-previous-page, #search-next-page').on('click', function() {
        navigateToSearchPage($(this).data('page'));
    });

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
    addWebsiteStructuredData();
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
